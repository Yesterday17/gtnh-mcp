package io.github.gtnhmcp;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.github.gtnhmcp.context.ContextStore;
import io.github.gtnhmcp.game.GameBridge;
import io.github.gtnhmcp.protocol.McpEndpoint;
import io.github.gtnhmcp.ui.ContextSharingUi;

public class ClientProxy extends CommonProxy {

    private LocalConfig config;
    private McpEndpoint endpoint;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        try {
            config = LocalConfig.load(
                event.getModConfigurationDirectory()
                    .toPath());
        } catch (Exception e) {
            GtnhMcpMod.LOG.error("GTNH MCP disabled: cannot load local configuration: {}", e.getMessage());
        }
    }

    @Override
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (config == null || !config.enabled) return;
        GameBridge bridge = null;
        try {
            ContextStore store = new ContextStore(
                config.path.getParent()
                    .resolve("gtnhmcp-context"));
            bridge = new GameBridge(store);
            final GameBridge ownedBridge = bridge;
            endpoint = new McpEndpoint(config.port, config.token, ToolCatalog.tools(), bridge::execute, store);
            endpoint.start();
            FMLCommonHandler.instance()
                .bus()
                .register(bridge);
            new ContextSharingUi(bridge).register();
            Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    endpoint.stop();
                    ownedBridge.close();
                }, "gtnhmcp-shutdown"));
            GtnhMcpMod.LOG.info(
                "GTNH MCP endpoint: {} (target pack {}). Token is in config/gtnhmcp.properties; it is never logged.",
                endpoint.getEndpointUrl(),
                GtnhMcpMod.TARGET_PACK);
        } catch (Exception e) {
            if (endpoint != null) endpoint.stop();
            if (bridge != null) bridge.close();
            GtnhMcpMod.LOG.error("GTNH MCP disabled: local listener could not start: {}", e.getMessage());
        }
    }
}
