package com.ultraview.lod.events;

import com.ultraview.lod.UltraViewLOD;
import com.ultraview.lod.config.LODConfig;
import com.ultraview.lod.core.LODManager;
import com.ultraview.lod.core.LODTransitionManager;
import com.ultraview.lod.render.ProjectionUtil;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.opengl.GL11;

public final class UVEvents {
    private final LODManager mgr;
    public UVEvents(LODManager mgr) { this.mgr = mgr; }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) mgr.onClientTick();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) { mgr.onDisconnect(); }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (UltraViewLOD.MODID.equals(e.modID)) {
            LODConfig.sync();
            UltraViewLOD.applyEntityRange();
            mgr.onConfigChanged();
        }
    }

    /** Amplia o plano distante da projecao do mundo inteiro (entidades distantes e LOD usam a mesma profundidade). */
    @SubscribeEvent
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup e) {
        if (!mgr.isActive()) return;
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        ProjectionUtil.ensureFar((float) (LODConfig.visualDistance * 1.15 + 64));
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    /** A neblina do Minecraft terminaria no limite dos chunks; usamos a do LOD para a transicao ficar continua. */
    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent e) {
        if (!mgr.isActive()) return;
        if (e.block.getMaterial() != Material.air) return;
        if (e.entity instanceof EntityLivingBase && ((EntityLivingBase) e.entity).isPotionActive(Potion.blindness)) return;
        GL11.glFogf(GL11.GL_FOG_START, LODTransitionManager.fogStart(LODConfig.visualDistance));
        GL11.glFogf(GL11.GL_FOG_END, LODTransitionManager.fogEnd(LODConfig.visualDistance));
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) { mgr.renderer.render(e.partialTicks); }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent e) {
        if (e.world != null && e.world.isRemote && e.pos != null) mgr.markUrgentBlock(e.pos);
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text e) {
        if (LODConfig.debugOverlay) e.left.addAll(mgr.debugLines());
    }
}
