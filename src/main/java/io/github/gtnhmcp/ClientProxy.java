package io.github.gtnhmcp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.github.gtnhmcp.config.ServerLifecycle;
import io.github.gtnhmcp.context.ContextStore;
import io.github.gtnhmcp.game.GameBridge;
import io.github.gtnhmcp.protocol.McpEndpoint;
import io.github.gtnhmcp.ui.ContextSharingUi;

public class ClientProxy extends CommonProxy {

    private LocalConfig config;
    private volatile ServerLifecycle lifecycle;
    private boolean initialized;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        try {
            config = LocalConfig.load(
                event.getModConfigurationDirectory()
                    .toPath());
        } catch (Exception failure) {
            // Do not include parser exception messages: a malformed value may contain the private token.
            GtnhMcpMod.LOG.error("GTNH MCP configuration could not be loaded. Check config/gtnhmcp.properties.");
        }
    }

    @Override
    public synchronized void loadComplete(FMLLoadCompleteEvent event) {
        if (initialized) return;
        initialized = true;
        if (config == null) return;
        GameBridge bridge = null;
        AtomicBoolean bridgeClosed = new AtomicBoolean();
        try {
            ContextStore store = new ContextStore(
                config.path.getParent()
                    .resolve("gtnhmcp-context"));
            bridge = new GameBridge(store);
            final GameBridge ownedBridge = bridge;
            // The store, tick handlers and capture UI exist once for the whole client lifetime, even when the
            // listener starts disabled. Rebinding changes only the endpoint, preserving drafts, world and messages.
            lifecycle = new ServerLifecycle(config, next -> {
                McpEndpoint endpoint = new McpEndpoint(
                    next.port,
                    next.token,
                    ToolCatalog.tools(),
                    ownedBridge::execute,
                    store);
                return new ServerLifecycle.Endpoint() {

                    @Override
                    public void start() throws Exception {
                        endpoint.start();
                    }

                    @Override
                    public void close() {
                        endpoint.stop();
                    }
                };
            }, LocalConfig::save);
            FMLCommonHandler.instance()
                .bus()
                .register(bridge);
            new ContextSharingUi(bridge).register();
            final ServerLifecycle ownedLifecycle = lifecycle;
            Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    try {
                        ownedLifecycle.closeAsync()
                            .get(15, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        GtnhMcpMod.LOG.warn("GTNH MCP listener shutdown did not finish before the shutdown deadline.");
                    } finally {
                        if (bridgeClosed.compareAndSet(false, true)) ownedBridge.close();
                    }
                }, "gtnhmcp-shutdown"));
            lifecycle.start()
                .thenAccept(result -> {
                    ServerLifecycle.State state = ownedLifecycle.state();
                    if (result.success && state.running) {
                        GtnhMcpMod.LOG.info(
                            "GTNH MCP endpoint: http://127.0.0.1:{}/mcp (target pack {}). Token stays in config/gtnhmcp.properties.",
                            state.port,
                            GtnhMcpMod.TARGET_PACK);
                    } else if (!result.success) {
                        GtnhMcpMod.LOG.warn(
                            "GTNH MCP listener did not start ({}). Use Mods > GTNH MCP > Config to retry or change the port.",
                            result.errorCode);
                    }
                });
        } catch (Exception failure) {
            if (lifecycle != null) lifecycle.closeAsync();
            lifecycle = null;
            if (bridge != null && bridgeClosed.compareAndSet(false, true)) {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(bridge);
                bridge.close();
            }
            GtnhMcpMod.LOG.error("GTNH MCP client initialization failed. Local context storage may be unavailable.");
        }
    }

    /** Contains no token and never waits for an endpoint's start/stop lock. Null means startup configuration failed. */
    public ServerLifecycle.State serverSettings() {
        ServerLifecycle current = lifecycle;
        return current == null ? null : current.state();
    }

    public CompletableFuture<ServerLifecycle.Result> applyServerSettings(boolean enabled, int port) {
        ServerLifecycle current = lifecycle;
        if (current == null) {
            CompletableFuture<ServerLifecycle.Result> failure = new CompletableFuture<>();
            failure.completeExceptionally(new IllegalStateException("Local configuration is unavailable"));
            return failure;
        }
        return current.apply(enabled, port);
    }

    /** Sharing remains available while the listener is disabled; consumers can receive the queued messages later. */
    public static boolean isLocalServerRunning() {
        if (!(GtnhMcpMod.proxy instanceof ClientProxy)) return false;
        ServerLifecycle.State state = ((ClientProxy) GtnhMcpMod.proxy).serverSettings();
        return state != null && state.running;
    }
}
