package com.ultraview.lod.render;

import com.ultraview.lod.config.LODConfig;
import com.ultraview.lod.core.DistantRepresentationCache;
import com.ultraview.lod.core.LODManager;
import com.ultraview.lod.core.LODTransitionManager;
import com.ultraview.lod.data.Region;
import com.ultraview.lod.mesh.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/** Desenha o terreno distante (listas de exibicao) na etapa RenderWorldLast, sempre na thread principal. */
public final class DistantTerrainRenderer {
    private final LODManager mgr;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final FloatBuffer proj = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer amb = BufferUtils.createFloatBuffer(4);

    public DistantTerrainRenderer(LODManager mgr) { this.mgr = mgr; }

    public GLMesh upload(MeshData m) {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int tex = TerrainTextureManager.create(m.texSize, m.texRgba);
        int list = GL11.glGenLists(1);
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glBegin(GL11.GL_QUADS);
        float[] v = m.verts;
        for (int i = 0; i < m.vertCount; i++) {
            int o = i * 6;
            float s = v[o + 5];
            GL11.glColor3f(s, s, s);
            GL11.glTexCoord2f(v[o + 3], v[o + 4]);
            GL11.glVertex3f(v[o], v[o + 1], v[o + 2]);
        }
        GL11.glEnd();
        GL11.glEndList();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        return new GLMesh(list, tex, m.estimatedBytes, m.level);
    }

    public void render(float pt) {
        if (!mgr.isActive()) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null || mc.theWorld == null) return;
        long t0 = System.nanoTime();
        mgr.processUploads(LODConfig.uploadBudgetMs * 1000000L);

        double ex = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double ey = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double ez = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        float yaw = (view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * pt) * 0.017453292f;
        float pit = (view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * pt) * 0.017453292f;
        double lx = -Math.sin(yaw) * Math.cos(pit), ly = -Math.sin(pit), lz = Math.cos(yaw) * Math.cos(pit);

        proj.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, proj);
        float m0 = proj.get(0), m5 = proj.get(5), m11 = proj.get(11);
        if (Math.abs(m11 + 1.0f) > 0.01f || m0 == 0 || m5 == 0) return;
        double tanH = 1.0 / m0, tanV = 1.0 / m5;
        double diagHalf = Math.atan(Math.sqrt(tanH * tanH + tanV * tanV));

        double dist = LODConfig.visualDistance;
        float sun = mc.theWorld.getSunBrightness(pt);
        float rain = mc.theWorld.getRainStrength(pt);
        float b = Math.min(1.0f, 0.3f + 0.7f * sun * (1.0f - 0.3f * rain));

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lmOn = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        ProjectionUtil.ensureFar((float) (dist * 1.15 + 64));
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_FOG);
        GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
        GL11.glFogf(GL11.GL_FOG_START, LODTransitionManager.fogStart(dist));
        GL11.glFogf(GL11.GL_FOG_END, LODTransitionManager.fogEnd(dist));
        GL11.glDisable(GL11.GL_LIGHT0);
        GL11.glDisable(GL11.GL_LIGHT1);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
        amb.clear();
        amb.put(b).put(b).put(b).put(1.0f);
        amb.flip();
        GL11.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, amb);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        int drawn = 0;
        long tick = mgr.tick();
        long[] keys = mgr.wantedKeys();
        DistantRepresentationCache cache = mgr.cache();
        for (int i = 0; i < keys.length; i++) {
            Object o = cache.get(keys[i]);
            if (o == null) continue;
            GLMesh mesh = (GLMesh) o;
            int rx = Region.keyX(keys[i]), rz = Region.keyZ(keys[i]);
            double dx = rx * 256.0 + 128.0 - ex, dz = rz * 256.0 + 128.0 - ez;
            double d2 = Math.sqrt(dx * dx + dz * dz);
            if (d2 > dist + 181) continue;
            double dy = 96.0 - ey;
            double len = Math.sqrt(d2 * d2 + dy * dy);
            if (len > 220) {
                double dot = (dx * lx + dy * ly + dz * lz) / len;
                double ang = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
                if (ang > diagHalf + Math.atan2(200.0, len) + 0.25) continue;
            }
            mesh.lastUse = tick;
            GL11.glPushMatrix();
            GL11.glTranslated(rx * 256.0 - ex, -ey, rz * 256.0 - ez);
            GL11.glCallList(mesh.list);
            GL11.glPopMatrix();
            drawn++;
        }

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        if (lmOn) GL11.glEnable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);

        mgr.profiler.regionsDrawn = drawn;
        mgr.profiler.recordRender(System.nanoTime() - t0);
    }
}
