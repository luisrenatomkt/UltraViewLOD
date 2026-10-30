package com.ultraview.lod.occlusion;

import com.ultraview.lod.config.LODConfig;
import com.ultraview.lod.core.LODManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Regras de visibilidade de jogadores. NAO substitui renderizacao/animacao: so cancela o desenho quando
 * nao ha linha de visao (raycast no mundo real + geometria do LOD). Nunca desativa o teste de profundidade
 * e nao existe configuracao para desligar esta protecao.
 */
public final class EntityVisibilityManager {
    private static final double VANILLA_RANGE = 64.0;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final LODManager mgr;
    private static final class Vis { long tick; boolean visible; }
    private final Map<Integer, Vis> bodyCache = new HashMap<Integer, Vis>();
    private final Map<Integer, Vis> nameCache = new HashMap<Integer, Vis>();

    public EntityVisibilityManager(LODManager mgr) { this.mgr = mgr; }

    public void clear() { bodyCache.clear(); nameCache.clear(); }

    private boolean isOther(Entity e) {
        return e instanceof EntityPlayer && e != mc.thePlayer && e != mc.getRenderViewEntity();
    }

    @SubscribeEvent
    public void onRenderLiving(RenderLivingEvent.Pre e) {
        if (!mgr.isActive() || mc.theWorld == null) return;
        double dist = Math.sqrt(e.x * e.x + e.y * e.y + e.z * e.z);
        if (e.entity instanceof EntityPlayer) {
            // dentro do alcance normal: vale a profundidade do proprio Minecraft; alem dele exigimos linha de visao
            if (isOther(e.entity) && dist > VANILLA_RANGE && !visible((EntityPlayer) e.entity, false)) e.setCanceled(true);
        } else if (LODConfig.hideFarNonPlayers && dist > VANILLA_RANGE * 1.25) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRenderName(RenderLivingEvent.Specials.Pre e) {
        if (!mgr.isActive() || mc.theWorld == null) return;
        if (isOther(e.entity) && !visible((EntityPlayer) e.entity, true)) e.setCanceled(true);
    }

    private boolean visible(EntityPlayer p, boolean forName) {
        long t = mgr.tick();
        Map<Integer, Vis> cache = forName ? nameCache : bodyCache;
        Vis v = cache.get(p.getEntityId());
        if (v != null && v.tick == t) return v.visible;
        boolean r = compute(p, forName);
        if (v == null) { v = new Vis(); cache.put(p.getEntityId(), v); }
        v.tick = t;
        v.visible = r;
        if (cache.size() > 512) cache.clear();
        return r;
    }

    private boolean compute(EntityPlayer p, boolean forName) {
        World w = mc.theWorld;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return false;
        if (!mgr.isRealChunkLoaded((int) Math.floor(p.posX), (int) Math.floor(p.posZ))) return false; // desconhecido => oculta
        Vec3 eye = view.getPositionEyes(1.0f);
        double h = p.height;
        double[] ys = forName
            ? new double[]{p.posY + h + 0.5, p.posY + h * 0.92, p.posY + h * 0.5}
            : new double[]{p.posY + h * 0.92, p.posY + h * 0.5, p.posY + 0.15};
        for (double y : ys) {
            Vec3 to = new Vec3(p.posX, y, p.posZ);
            if (w.rayTraceBlocks(eye, to, false, true, false) != null) continue;
            if (OcclusionManager.lodBlocks(mgr, eye.xCoord, eye.yCoord, eye.zCoord, p.posX, y, p.posZ, 2.0, 1.5)) continue;
            return true;
        }
        return false;
    }
}
