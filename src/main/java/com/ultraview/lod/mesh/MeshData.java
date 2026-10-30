package com.ultraview.lod.mesh;

/** Resultado de uma construcao de malha em segundo plano (sem objetos OpenGL). */
public final class MeshData {
    /** x,y,z,u,v,shade por vertice; quads. */
    public float[] verts;
    public int vertCount;
    public byte[] texRgba;
    public int texSize;
    public int level;
    public long estimatedBytes;
}
