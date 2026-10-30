package com.ultraview.lod;

import com.ultraview.lod.command.UVCommand;
import com.ultraview.lod.config.LODConfig;
import com.ultraview.lod.core.LODManager;
import com.ultraview.lod.events.UVEvents;
import com.ultraview.lod.occlusion.EntityVisibilityManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Mod(modid = UltraViewLOD.MODID, name = "UltraView LOD", version = "1.0.0", clientSideOnly = true,
     acceptedMinecraftVersions = "[1.8.9]", guiFactory = "com.ultraview.lod.config.UVGuiFactory")
public class UltraViewLOD {
    public static final String MODID = "ultraviewlod";
    public static Logger LOGGER;
    public static LODManager manager;
    private static boolean warned;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        LOGGER = e.getModLog();
        LODConfig.init(e.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        manager = new LODManager();
        UVEvents events = new UVEvents(manager);
        EntityVisibilityManager vis = new EntityVisibilityManager(manager);
        MinecraftForge.EVENT_BUS.register(events);
        MinecraftForge.EVENT_BUS.register(vis);
        FMLCommonHandler.instance().bus().register(events);
        ClientCommandHandler.instance.registerCommand(new UVCommand());
        applyEntityRange();
        LOGGER.info("UltraView LOD carregado (somente cliente).");
    }

    /** Ajusta o alcance de renderizacao de entidades do proprio Minecraft (nao altera alcance de ataque nem o que o servidor envia). */
    public static void applyEntityRange() {
        double weight = Math.max(1.0, Math.min(8.0, LODConfig.entityRenderDistance / 64.0));
        try {
            Field f = ReflectionHelper.findField(Entity.class, "renderDistanceWeight", "field_70155_l");
            if (Modifier.isStatic(f.getModifiers())) f.setDouble(null, weight);
            else if (!warned) { warned = true; LOGGER.warn("renderDistanceWeight nao e estatico; alcance estendido de jogadores indisponivel."); }
        } catch (Throwable t) {
            if (!warned) { warned = true; LOGGER.warn("Nao foi possivel ajustar o alcance de entidades: " + t); }
        }
    }
}
