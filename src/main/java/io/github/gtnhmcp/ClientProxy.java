package io.github.gtnhmcp;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.github.gtnhmcp.game.GameBridge;
import io.github.gtnhmcp.protocol.McpEndpoint;

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
        final GameBridge bridge = new GameBridge();
        try {
            endpoint = new McpEndpoint(config.port, config.token, ToolCatalog.tools(), bridge::execute);
            endpoint.start();
            FMLCommonHandler.instance()
                .bus()
                .register(bridge);
            Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    endpoint.stop();
                    bridge.close();
                }, "gtnhmcp-shutdown"));
            GtnhMcpMod.LOG.info(
                "GTNH MCP read-only endpoint: {} (target pack {}). Token is in config/gtnhmcp.properties; it is never logged.",
                endpoint.getEndpointUrl(),
                GtnhMcpMod.TARGET_PACK);
        } catch (Exception e) {
            if (endpoint != null) endpoint.stop();
            GtnhMcpMod.LOG.error("GTNH MCP disabled: local listener could not start: {}", e.getMessage());
        }
    }
}
