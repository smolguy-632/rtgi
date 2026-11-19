package com.rtgi;

import com.rtgi.client.GIManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Client-only. No mixins and no chunk-renderer hooks, so it is independent of Celeritas/vanilla terrain rendering. */
@Mod(modid = Tags.MODID, name = Tags.MODNAME, version = Tags.VERSION,
     acceptedMinecraftVersions = "[1.12.2]", clientSideOnly = true,
     dependencies = "after:celeritas;after:mixinbooter")
public class RTGIMod {
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(new GIManager());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (Tags.MODID.equals(e.getModID())) ConfigManager.sync(Tags.MODID, Config.Type.INSTANCE);
    }
}
