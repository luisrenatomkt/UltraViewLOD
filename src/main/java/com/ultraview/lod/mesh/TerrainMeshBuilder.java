package com.ultraview.lod.mesh;

import com.ultraview.lod.data.Region;

/** Gera heightfield simplificado (celulas de 4/8/16/32 blocos) e a textura de cores da regiao. */
public final class TerrainMeshBuilder {
    public static final float SHADE_TOP = 1.0f, SHADE_X = 0.78f, SHADE_Z = 0.62f;
    private static final float R = Region.BLOCKS;

    private TerrainMeshBuilder() { }

    private static final class FloatBuf {
        float[] a = new float[8192];
        int n;
        void v(float x, float y, float z, float u, float v, float s) {
            if (n + 6 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x; a[n++] = y; a[n++] = z; a[n++] = u; a[n++] = v; a[n++] = s;
        }
    }

    /** Quanto as paredes de borda de regiao descem abaixo do vizinho para esconder frestas entre niveis de detalhe. */
    private static final int SKIRT = 2;

    /**
     * @param heights topo maximo por amostra (4x4 blocos); a malha usa heights - queda (flags), isto e, o CHAO medio
     * @param edges   alturas de malha das amostras de borda das regioes vizinhas alinhadas a esta: [0]=oeste, [1]=leste, [2]=norte, [3]=sul (null = desconhecido)
     * @param mask    chunks que o Minecraft ja desenha normalmente (celulas totalmente cobertas nao geram geometria)
     */
    public static MeshData build(short[] heights, byte[] flags, int[] colors, int level, long[] mask, int[][] edges, int texSize) {
        final int k = 1 << level;
        final int n = Region.SIZE / k;
        final int size = k * 4;
        // Altura da celula = MEDIA do chao das amostras conhecidas (o maximo inflava colinas em "mesas" e
        // fazia o LOD cobrir o terreno real na emenda com os chunks normais).
        int[] cell = new int[n * n];
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                int sum = 0, cnt = 0;
                for (int dz = 0; dz < k; dz++)
                    for (int dx = 0; dx < k; dx++) {
                        int idx = (cz * k + dz) * Region.SIZE + cx * k + dx;
                        if (heights[idx] == Region.UNKNOWN) continue;
                        sum += Region.meshHeight(heights[idx], flags[idx]);
                        cnt++;
                    }
                cell[cz * n + cx] = cnt == 0 ? Region.UNKNOWN : Math.round(sum / (float) cnt);
            }
        }
        FloatBuf fb = new FloatBuf();
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                int h = cell[cz * n + cx];
                if (h == Region.UNKNOWN || cellMasked(mask, cx, cz, k)) continue;
                int x0 = cx * size, x1 = x0 + size, z0 = cz * size, z1 = z0 + size;
                float u0 = x0 / R, u1 = x1 / R, v0 = z0 / R, v1 = z1 / R;
                fb.v(x0, h, z0, u0, v0, SHADE_TOP);
                fb.v(x1, h, z0, u1, v0, SHADE_TOP);
                fb.v(x1, h, z1, u1, v1, SHADE_TOP);
                fb.v(x0, h, z1, u0, v1, SHADE_TOP);
                float uc = (x0 + x1) * 0.5f / R, vc = (z0 + z1) * 0.5f / R;
                boolean bw = cx == 0, be = cx == n - 1, bn = cz == 0, bs = cz == n - 1;
                int w = !bw ? cell[cz * n + cx - 1] : edgeVal(edges, 0, cz * k, k);
                int e = !be ? cell[cz * n + cx + 1] : edgeVal(edges, 1, cz * k, k);
                int no = !bn ? cell[(cz - 1) * n + cx] : edgeVal(edges, 2, cx * k, k);
                int so = !bs ? cell[(cz + 1) * n + cx] : edgeVal(edges, 3, cx * k, k);
                // Dentro da regiao: parede so do lado mais alto, ate o vizinho (sem sobreposicao).
                // Na borda da regiao o vizinho pode ter outra resolucao: a parede desce SKIRT blocos a mais (fica
                // enterrada no vizinho) para nao abrir frestas por onde se ve o ceu.
                if (w != Region.UNKNOWN && (bw ? w <= h : w < h)) wallX(fb, x0, bw ? w - SKIRT : w, h, z0, z1, uc, vc, SHADE_X);
                if (e != Region.UNKNOWN && (be ? e <= h : e < h)) wallX(fb, x1, be ? e - SKIRT : e, h, z0, z1, uc, vc, SHADE_X);
                if (no != Region.UNKNOWN && (bn ? no <= h : no < h)) wallZ(fb, z0, bn ? no - SKIRT : no, h, x0, x1, uc, vc, SHADE_Z);
                if (so != Region.UNKNOWN && (bs ? so <= h : so < h)) wallZ(fb, z1, bs ? so - SKIRT : so, h, x0, x1, uc, vc, SHADE_Z);
            }
        }
        MeshData m = new MeshData();
        m.verts = java.util.Arrays.copyOf(fb.a, fb.n);
        m.vertCount = fb.n / 6;
        m.level = level;
        if (texSize <= 0 || Region.SIZE % texSize != 0) texSize = Region.SIZE;
        m.texSize = texSize;
        m.texRgba = buildTexture(colors, texSize);
        m.estimatedBytes = m.vertCount * 20L + (long) texSize * texSize * 4L * 4L / 3L;
        return m;
    }

    private static void wallX(FloatBuf fb, float x, float lo, float hi, float z0, float z1, float u, float v, float s) {
        fb.v(x, lo, z0, u, v, s); fb.v(x, lo, z1, u, v, s); fb.v(x, hi, z1, u, v, s); fb.v(x, hi, z0, u, v, s);
    }
    private static void wallZ(FloatBuf fb, float z, float lo, float hi, float x0, float x1, float u, float v, float s) {
        fb.v(x0, lo, z, u, v, s); fb.v(x1, lo, z, u, v, s); fb.v(x1, hi, z, u, v, s); fb.v(x0, hi, z, u, v, s);
    }

    private static int edgeVal(int[][] edges, int side, int start, int k) {
        if (edges == null || edges[side] == null) return Region.UNKNOWN;
        int sum = 0, cnt = 0;
        for (int i = 0; i < k; i++) {
            int h = edges[side][start + i];
            if (h != Region.UNKNOWN) { sum += h; cnt++; }
        }
        return cnt == 0 ? Region.UNKNOWN : Math.round(sum / (float) cnt);
    }

    private static boolean cellMasked(long[] mask, int cx, int cz, int k) {
        if (mask == null) return false;
        int lx0 = (cx * k) >> 2, lx1 = (cx * k + k - 1) >> 2, lz0 = (cz * k) >> 2, lz1 = (cz * k + k - 1) >> 2;
        for (int lz = lz0; lz <= lz1; lz++)
            for (int lx = lx0; lx <= lx1; lx++) {
                int idx = lz * 16 + lx;
                if (((mask[idx >> 6] >>> (idx & 63)) & 1L) == 0) return false;
            }
        return true;
    }

    public static byte[] buildTexture(int[] colors, int texSize) {
        int f = Region.SIZE / texSize;
        long mr = 0, mg = 0, mb = 0, mc = 0;
        for (int c : colors) {
            if ((c >>> 24) != 0) { mr += (c >> 16) & 255; mg += (c >> 8) & 255; mb += c & 255; mc++; }
        }
        int dr = mc > 0 ? (int) (mr / mc) : 128, dg = mc > 0 ? (int) (mg / mc) : 128, db = mc > 0 ? (int) (mb / mc) : 128;
        byte[] tex = new byte[texSize * texSize * 4];
        for (int ty = 0; ty < texSize; ty++) {
            for (int tx = 0; tx < texSize; tx++) {
                long r = 0, g = 0, b = 0, c = 0;
                for (int dz = 0; dz < f; dz++)
                    for (int dx = 0; dx < f; dx++) {
                        int col = colors[(ty * f + dz) * Region.SIZE + tx * f + dx];
                        if ((col >>> 24) != 0) { r += (col >> 16) & 255; g += (col >> 8) & 255; b += col & 255; c++; }
                    }
                int o = (ty * texSize + tx) * 4;
                tex[o] = (byte) (c > 0 ? r / c : dr);
                tex[o + 1] = (byte) (c > 0 ? g / c : dg);
                tex[o + 2] = (byte) (c > 0 ? b / c : db);
                tex[o + 3] = (byte) 255;
            }
        }
        return tex;
    }
}
