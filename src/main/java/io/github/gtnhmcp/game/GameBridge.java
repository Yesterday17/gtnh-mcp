package io.github.gtnhmcp.game;

import static io.github.gtnhmcp.game.Values.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.gtnhmcp.GtnhMcpMod;
import io.github.gtnhmcp.nei.NeiService;
import io.github.gtnhmcp.protocol.ToolExecutionException;

public final class GameBridge {

    private final GameTaskQueue clientQueue = new GameTaskQueue(8);
    private final GameTaskQueue serverQueue = new GameTaskQueue(8);
    private final NeiService nei = new NeiService();
    private World previousWorld;
    private volatile String worldSession = "no_world";
    private volatile long clientTicks;
    private volatile long serverTicks;
    private volatile boolean closed;

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (closed || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != previousWorld) {
            previousWorld = mc.theWorld;
            worldSession = mc.theWorld == null ? "no_world"
                : UUID.randomUUID()
                    .toString();
            clientQueue.invalidate();
            serverQueue.invalidate();
            nei.invalidate();
        }
        clientTicks++;
        clientQueue.drain(2, 2_000_000L);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (closed || event.phase != TickEvent.Phase.END) return;
        serverTicks++;
        serverQueue.drain(2, 2_000_000L);
    }

    /** Called on a protocol worker. All game access occurs inside the relevant queue. */
    public Map<String, Object> execute(String tool, Map<String, Object> arguments) throws Exception {
        if (closed) throw new ToolExecutionException("stopped", "The game bridge has stopped.");
        String expectedSession = worldSession;
        try {
            Map<String, Object> result;
            if ("get_context".equals(tool)) {
                return clientQueue.submitAndWait(this::context, 3500);
            } else if (Arrays.asList("search_items", "find_recipes", "find_usages", "get_recipe")
                .contains(tool)) {
                    result = clientQueue.submitAndWait(() -> {
                        requireWorld();
                        return stamp(nei.call(tool, arguments), "nei_client", clientTicks);
                    }, 3500);
                } else {
                    Request request = clientQueue.submitAndWait(() -> capture(tool, arguments), 1000);
                    if (request.clientResult != null) {
                        result = request.clientResult;
                    } else {
                        if (request.paused) throw new ToolExecutionException(
                            "world_paused",
                            "Resume the singleplayer world to obtain a fresh server snapshot.");
                        result = serverQueue.submitAndWait(() -> serverRead(tool, arguments, request), 2500);
                    }
                }
            if (!worldSession.equals(expectedSession)) {
                throw new ToolExecutionException("world_changed", "The world changed during the request. Query again.");
            }
            return result;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new ToolExecutionException(
                "game_not_ticking",
                "The game did not respond before the deadline. It may be paused or loading.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            String message = e.getMessage() == null ? "Invalid request or unavailable game state" : e.getMessage();
            throw new ToolExecutionException(
                "game_request_failed",
                message.substring(0, Math.min(message.length(), 1024)));
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new ToolExecutionException("cancelled", "The request was cancelled.");
        } catch (Exception e) {
            GtnhMcpMod.LOG.warn("GTNH MCP tool {} failed", tool, e);
            throw new ToolExecutionException("game_error", "The game adapter failed. See the local game log.");
        }
    }

    private Map<String, Object> context() {
        Minecraft mc = Minecraft.getMinecraft();
        Map<String, Object> versions = map();
        for (String id : Arrays.asList("NotEnoughItems", "gregtech", "gtnhlib", "gtnhcore")) {
            ModContainer mod = Loader.instance()
                .getIndexedModList()
                .get(id);
            if (mod != null) versions.put(id, mod.getVersion());
        }
        boolean local = mc.isIntegratedServerRunning();
        return stamp(
            map(
                "targetPack",
                GtnhMcpMod.TARGET_PACK,
                "installedMods",
                versions,
                "worldLoaded",
                mc.theWorld != null,
                "mode",
                mc.theWorld == null ? "menu" : local ? "singleplayer" : "multiplayer_client",
                "paused",
                paused(mc),
                "player",
                mc.thePlayer == null ? null : GameSnapshots.player(mc.thePlayer),
                "capabilities",
                map(
                    "recipes",
                    mc.theWorld != null,
                    "playerInventory",
                    mc.thePlayer != null,
                    "authoritativeWorldReads",
                    local && mc.theWorld != null,
                    "writeActions",
                    false),
                "summary",
                "GTNH MCP 0.1.0; read-only, NEI recipe queries and bounded world snapshots."),
            "client",
            clientTicks);
    }

    private Request capture(String tool, Map<String, Object> args) throws ToolExecutionException {
        Minecraft mc = requireWorld();
        Request request = new Request();
        request.session = worldSession;
        request.playerId = mc.thePlayer.getUniqueID();
        request.dimension = mc.thePlayer.dimension;
        request.server = mc.getIntegratedServer();
        request.paused = paused(mc);
        if ("inspect_target".equals(tool)) {
            MovingObjectPosition hit = mc.objectMouseOver;
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                throw new ToolExecutionException("no_block_target", "Point the crosshair at a block first.");
            }
            request.x = hit.blockX;
            request.y = hit.blockY;
            request.z = hit.blockZ;
            if (!mc.isIntegratedServerRunning()) {
                request.clientResult = stamp(
                    GameSnapshots.block(mc.theWorld, request.x, request.y, request.z, false),
                    "client_synced",
                    clientTicks);
            }
        } else if ("inspect_machine".equals(tool)
            || ("get_inventory".equals(tool) && "container".equals(args.get("scope")))) {
                if (!args.containsKey("x") || !args.containsKey("y") || !args.containsKey("z")) {
                    throw new IllegalArgumentException("Container/machine queries require x, y and z");
                }
                request.x = Values.integer(args, "x", 0, -30000000, 30000000);
                request.y = Values.integer(args, "y", 0, 0, 255);
                request.z = Values.integer(args, "z", 0, -30000000, 30000000);
                if (mc.thePlayer.getDistanceSq(request.x + 0.5, request.y + 0.5, request.z + 0.5) > 64 * 64) {
                    throw new IllegalArgumentException("out_of_range: target must be within 64 blocks of the player");
                }
            } else if ("get_inventory".equals(tool) && !mc.isIntegratedServerRunning()) {
                request.clientResult = stamp(
                    map("scope", "player", "inventory", GameSnapshots.inventory(mc.thePlayer.inventory)),
                    "client_synced",
                    clientTicks);
            }
        if (request.clientResult == null && (!mc.isIntegratedServerRunning() || request.server == null)) {
            throw new ToolExecutionException(
                "singleplayer_required",
                "This tool needs the local integrated server. It is unavailable on a remote multiplayer server.");
        }
        return request;
    }

    private Map<String, Object> serverRead(String tool, Map<String, Object> args, Request request)
        throws ToolExecutionException {
        if (!request.session.equals(worldSession))
            throw new ToolExecutionException("world_changed", "The world changed; query again.");
        WorldServer world = null;
        for (WorldServer candidate : request.server.worldServers) {
            if (candidate != null && candidate.provider.dimensionId == request.dimension) world = candidate;
        }
        if (world == null)
            throw new ToolExecutionException("dimension_unavailable", "The player's dimension is no longer loaded.");
        EntityPlayerMP player = null;
        for (Object value : request.server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP candidate = (EntityPlayerMP) value;
            if (candidate.getUniqueID()
                .equals(request.playerId)) player = candidate;
        }
        if (player == null || player.dimension != request.dimension)
            throw new ToolExecutionException("player_changed", "The player disconnected or changed dimension.");
        Map<String, Object> result;
        if ("get_inventory".equals(tool)) {
            if ("container".equals(args.get("scope"))) {
                requireLoaded(world, request);
                TileEntity tile = world.getTileEntity(request.x, request.y, request.z);
                if (!(tile instanceof IInventory))
                    throw new ToolExecutionException("not_inventory", "The target does not expose an IInventory.");
                result = map(
                    "scope",
                    "container",
                    "dimension",
                    request.dimension,
                    "x",
                    request.x,
                    "y",
                    request.y,
                    "z",
                    request.z,
                    "inventory",
                    GameSnapshots.inventory((IInventory) tile));
            } else {
                result = map("scope", "player", "inventory", GameSnapshots.inventory(player.inventory));
            }
        } else if ("inspect_target".equals(tool) || "inspect_machine".equals(tool)) {
            result = GameSnapshots.block(world, request.x, request.y, request.z, true);
        } else if ("find_machines".equals(tool)) {
            int radius = Values.integer(args, "radius", 16, 1, 32);
            int limit = Values.integer(args, "limit", 30, 1, 100);
            List<Object> tiles = new ArrayList<>();
            boolean complete = true;
            int examined = 0;
            for (Object value : world.loadedTileEntityList) {
                if (++examined > 10000) {
                    complete = false;
                    break;
                }
                if (!(value instanceof TileEntity)) continue;
                TileEntity tile = (TileEntity) value;
                if (!tile.isInvalid() && player.getDistanceSq(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5)
                    <= radius * radius) {
                    if (tiles.size() >= limit) {
                        complete = false;
                        break;
                    }
                    tiles.add(
                        map(
                            "x",
                            tile.xCoord,
                            "y",
                            tile.yCoord,
                            "z",
                            tile.zCoord,
                            "tileType",
                            tile.getClass()
                                .getName()));
                }
            }
            result = map(
                "dimension",
                request.dimension,
                "radius",
                radius,
                "tiles",
                tiles,
                "complete",
                complete,
                "coverage",
                "loaded_tile_entities_only; includes_non_machines");
        } else {
            throw new IllegalArgumentException("Unknown tool: " + tool);
        }
        return stamp(result, "integrated_server", serverTicks);
    }

    private static void requireLoaded(World world, Request request) throws ToolExecutionException {
        if (!world.blockExists(request.x, request.y, request.z)) {
            throw new ToolExecutionException("chunk_not_loaded", "The target chunk is not loaded.");
        }
    }

    private static Minecraft requireWorld() throws ToolExecutionException {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null)
            throw new ToolExecutionException("no_world", "Enter a world before calling this tool.");
        return mc;
    }

    private static boolean paused(Minecraft mc) {
        return mc.isIntegratedServerRunning() && mc.currentScreen != null
            && mc.currentScreen.doesGuiPauseGame()
            && mc.getIntegratedServer() != null
            && !mc.getIntegratedServer()
                .getPublic();
    }

    private Map<String, Object> stamp(Map<String, Object> data, String source, long tick) {
        data.put("worldSessionId", worldSession);
        data.put("source", source);
        data.put("sampledTick", tick);
        data.put("sampledAtMillis", System.currentTimeMillis());
        return data;
    }

    public void close() {
        closed = true;
        clientQueue.invalidate();
        serverQueue.invalidate();
    }

    private static final class Request {

        private String session;
        private UUID playerId;
        private int dimension;
        private int x;
        private int y;
        private int z;
        private boolean paused;
        private MinecraftServer server;
        private Map<String, Object> clientResult;
    }
}
