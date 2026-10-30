package com.ultraview.lod.render;

import com.ultraview.lod.core.DistantRepresentationCache;
import org.lwjgl.opengl.GL11;

/** Display list + textura de uma regiao. Criar/liberar SOMENTE na thread principal (contexto OpenGL). */
public final class GLMesh implements DistantRepresentationCache.Handle {
    public final int list, tex;
    private final long bytes;
    public final int level;
    public volatile long lastUse;

    public GLMesh(int list, int tex, long bytes, int level) {
        this.list = list; this.tex = tex; this.bytes = bytes; this.level = level;
    }
    public long bytes() { return bytes; }
    public long lastUse() { return lastUse; }
    public void release() {
        GL11.glDeleteLists(list, 1);
        GL11.glDeleteTextures(tex);
    }
}
