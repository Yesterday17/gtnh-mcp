package io.github.gtnhmcp.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicitly enabled test fixture. This source set is never included in the release mod. */
@Mod(
    modid = "gtnhmcpacceptance",
    name = "GTNH MCP Acceptance Fixture",
    version = "0.1.0",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public final class AcceptanceFixture {

    private static final Logger LOG = LogManager.getLogger("gtnhmcp-acceptance");
    private Path directory;
    private String worldFolder;
    private boolean started;
    private boolean failed;
    private int clientTicks;
    private int playerTicks;
    private volatile boolean ready;
    private volatile String phase = "waiting_for_main_menu";
    private volatile String lastCommand = "";
    private MinecraftServer preparedServer;

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) throws IOException {
        if (!Boolean.getBoolean("gtnhmcp.acceptance") || !event.getSide()
            .isClient()) return;
        String requestedDirectory = System.getProperty("gtnhmcp.acceptance.dir");
        if (requestedDirectory == null || requestedDirectory.trim()
            .isEmpty()) {
            throw new IllegalArgumentException("Acceptance fixture requires gtnhmcp.acceptance.dir");
        }
        directory = Paths.get(requestedDirectory)
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(directory);
        worldFolder = System.getProperty("gtnhmcp.acceptance.world", "gtnhmcp-acceptance-rc2");
        if (!worldFolder.matches("gtnhmcp-acceptance-[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Acceptance world must use the disposable gtnhmcp-acceptance- prefix");
        }
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        writeStatus(Minecraft.getMinecraft());
        LOG.info("Acceptance fixture enabled; disposable world {}", worldFolder);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || directory == null || failed) return;
        Minecraft mc = Minecraft.getMinecraft();
        clientTicks++;
        try {
            // Avoid an unfocused test window pausing the integrated server automatically.
            mc.gameSettings.pauseOnLostFocus = false;
            if (!started && clientTicks >= 100 && mc.theWorld == null && mc.currentScreen != null) {
                started = true;
                launch(mc);
            }
            if (clientTicks % 5 == 0) {
                Path commandFile = directory.resolve("command.txt");
                if (Files.isRegularFile(commandFile)) {
                    String command = new String(Files.readAllBytes(commandFile), StandardCharsets.UTF_8).trim();
                    Files.delete(commandFile);
                    executeCommand(mc, command);
                }
            }
            if (clientTicks % 20 == 0) writeStatus(mc);
        } catch (Throwable error) {
            failed = true;
            phase = "failed";
            LOG.error("Acceptance fixture failed", error);
            try {
                writeStatus(mc);
            } catch (IOException ignored) {}
        }
    }

    private void launch(Minecraft mc) throws IOException {
        phase = "loading_world";
        ready = false;
        playerTicks = 0;
        writeStatus(mc);
        WorldSettings settings = new WorldSettings(
            20261030L,
            WorldSettings.GameType.CREATIVE,
            false,
            false,
            WorldType.FLAT).enableCommands()
                .func_82750_a("2;7,2x3,2;1;");
        mc.launchIntegratedServer(worldFolder, "Disposable GTNH MCP RC2 acceptance", settings);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || directory == null || failed) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server == preparedServer || server.getConfigurationManager().playerEntityList.isEmpty())
            return;
        if (++playerTicks < 40) return;
        try {
            WorldServer world = server.worldServerForDimension(0);
            EntityPlayerMP player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
            if (player.dimension != 0) return;
            world.difficultySetting = EnumDifficulty.PEACEFUL;
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.setWorldTime(6000);
            for (int x = -5; x <= 7; x++) {
                for (int z = -5; z <= 5; z++) {
                    world.setBlock(x, 64, z, Blocks.stone, 0, 3);
                    for (int y = 65; y <= 69; y++) {
                        TileEntity existing = world.getTileEntity(x, y, z);
                        if (existing instanceof IInventory) {
                            IInventory inventory = (IInventory) existing;
                            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                                inventory.setInventorySlotContents(slot, null);
                            }
                            existing.markDirty();
                        }
                        world.setBlockToAir(x, y, z);
                    }
                }
            }
            world.setBlock(2, 65, 0, Blocks.chest, 0, 3);
            TileEntityChest chest = (TileEntityChest) world.getTileEntity(2, 65, 0);
            chest.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 64));
            chest.setInventorySlotContents(1, new ItemStack(Items.redstone, 32));
            chest.setInventorySlotContents(2, new ItemStack(Blocks.log, 16));
            chest.markDirty();
            world.setBlock(4, 65, 0, Blocks.furnace, 0, 3);
            TileEntityFurnace furnace = (TileEntityFurnace) world.getTileEntity(4, 65, 0);
            furnace.setInventorySlotContents(0, new ItemStack(Blocks.iron_ore, 8));
            furnace.markDirty(); // Deliberately no fuel: a predictable idle furnace.
            world.setBlock(2, 65, 2, Blocks.crafting_table, 0, 3);
            // Rejoining must not pick up fixture contents dropped by an earlier reset.
            for (EntityItem drop : world
                .getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(-8, 60, -8, 10, 72, 10))) {
                drop.setDead();
            }
            for (int i = 0; i < player.inventory.mainInventory.length; i++) player.inventory.mainInventory[i] = null;
            for (int i = 0; i < player.inventory.armorInventory.length; i++) player.inventory.armorInventory[i] = null;
            player.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 32));
            player.inventory.setInventorySlotContents(1, new ItemStack(Items.redstone, 16));
            player.inventory.setInventorySlotContents(2, new ItemStack(Items.diamond, 3));
            player.inventory.currentItem = 0;
            player.setGameType(WorldSettings.GameType.CREATIVE);
            player.playerNetServerHandler.setPlayerLocation(0.5D, 65.0D, 0.5D, -90.0F, 35.0F);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            preparedServer = server;
            ready = true;
            phase = "world_ready";
            LOG.info(
                "ACCEPTANCE_WORLD_READY player=(0.5,65,0.5) chest=(2,65,0) furnace=(4,65,0) crafting_table=(2,65,2)");
        } catch (Throwable error) {
            failed = true;
            phase = "failed";
            LOG.error("Acceptance world preparation failed", error);
        }
    }

    private void executeCommand(Minecraft mc, String command) throws IOException {
        lastCommand = command;
        switch (command) {
            case "pause":
                if (mc.theWorld == null) throw new IllegalStateException("No world to pause");
                mc.displayGuiScreen(new GuiIngameMenu());
                phase = "paused";
                break;
            case "resume":
                if (mc.theWorld == null) throw new IllegalStateException("No world to resume");
                mc.displayGuiScreen(null);
                phase = "world_ready";
                break;
            case "unload":
                if (mc.theWorld != null) mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
                mc.displayGuiScreen(new GuiMainMenu());
                ready = false;
                phase = "unloaded";
                break;
            case "rejoin":
                if (mc.theWorld != null) throw new IllegalStateException("Unload before rejoining");
                launch(mc);
                break;
            case "quit":
                phase = "quitting";
                writeStatus(mc);
                mc.shutdown();
                break;
            default:
                throw new IllegalArgumentException("Unknown acceptance command: " + command);
        }
        writeStatus(mc);
        LOG.info("Acceptance command completed: {}", command);
    }

    private void writeStatus(Minecraft mc) throws IOException {
        String status = "{\n  \"phase\": \"" + phase
            + "\",\n  \"worldFolder\": \""
            + worldFolder
            + "\",\n  \"ready\": "
            + ready
            + ",\n  \"worldLoaded\": "
            + (mc.theWorld != null)
            + ",\n  \"paused\": "
            + mc.isGamePaused()
            + ",\n  \"lastCommand\": \""
            + lastCommand.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
            + "\",\n  \"updatedAtEpochMillis\": "
            + System.currentTimeMillis()
            + ",\n  \"fixtures\": {\"chest\": [2,65,0], \"furnace\": [4,65,0], \"craftingTable\": [2,65,2]},"
            + "\n  \"chestInventory\": {\"minecraft:iron_ingot\":64,\"minecraft:redstone\":32,\"minecraft:log\":16},"
            + "\n  \"playerInventory\": {\"minecraft:iron_ingot\":32,\"minecraft:redstone\":16,\"minecraft:diamond\":3}\n}\n";
        Path pending = directory.resolve("status.json.tmp");
        Files.write(pending, status.getBytes(StandardCharsets.UTF_8));
        Files.move(
            pending,
            directory.resolve("status.json"),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }
}
