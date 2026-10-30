package io.github.gtnhmcp;

import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** Dedicated servers do not start a listener or load any client classes. */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    public void loadComplete(FMLLoadCompleteEvent event) {}
}
