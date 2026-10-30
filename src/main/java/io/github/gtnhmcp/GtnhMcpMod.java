package io.github.gtnhmcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = GtnhMcpMod.MODID,
    name = "GTNH MCP",
    version = "0.2.0",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    dependencies = "required-after:NotEnoughItems@[2.8.155-GTNH,);after:gregtech")
public class GtnhMcpMod {

    public static final String MODID = "gtnhmcp";
    public static final String TARGET_PACK = "2.9.0-RC-2";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "io.github.gtnhmcp.ClientProxy", serverSide = "io.github.gtnhmcp.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        proxy.loadComplete(event);
    }
}
