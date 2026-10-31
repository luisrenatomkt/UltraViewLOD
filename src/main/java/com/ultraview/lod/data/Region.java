package com.ultraview.lod.data;

import java.util.Arrays;

/** Regiao de 16x16 chunks (256x256 blocos) com amostras 4x4 blocos. Sem dependencias do Minecraft. */
public final class Region {
    public static final int CHUNKS = 16;
    public static final int SAMPLES_PER_CHUNK = 4;
    public static final int SIZE = CHUNKS * SAMPLES_PER_CHUNK;
    public static final int BLOCKS = CHUNKS * 16;
    public static final short UNKNOWN = Short.MIN_VALUE;
    public static final int BYTES = SIZE * SIZE * 7;
    /** flags: bit0 = agua; bits2..7 = queda (topo maximo - altura media do chao) usada pela malha. */
    public static final int DROP_SHIFT = 2, DROP_MAX = 63;

    /** Altura usada para desenhar o terreno (chao medio da celula); UNKNOWN (como int) se desconhecida. */
    public static int meshHeight(short h, byte f) {
        if (h == UNKNOWN) return UNKNOWN;
        return h - ((f >> DROP_SHIFT) & DROP_MAX);
    }
    public int meshHeightAt(int idx) { return meshHeight(heights[idx], flags[idx]); }
    public boolean isComplete() {
        for (short h : heights) if (h == UNKNOWN) return false;
        return true;
    }

    public final int rx, rz;
    public final short[] heights = new short[SIZE * SIZE];
    public final int[] colors = new int[SIZE * SIZE];
    public final byte[] flags = new byte[SIZE * SIZE];
    public int dataVersion = 1;
    public boolean dirtyDisk;
    public boolean hasData;
    public long lastUpdateMillis;
    private final long[] mask = new long[4];
    public int maskVersion;
    // controle de malha (somente thread principal)
    public int levelBase = -1, builtLevel = -1, builtDataVersion = -1, builtMaskVersion = -1;
    public boolean building, reuseCounted;
    public long lastBuildTick = -1000;

    public Region(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
        Arrays.fill(heights, UNKNOWN);
    }

    public static long key(int rx, int rz) { return ((long) rx << 32) | (rz & 0xFFFFFFFFL); }
    public static int keyX(long k) { return (int) (k >> 32); }
    public static int keyZ(long k) { return (int) k; }
    public long key() { return key(rx, rz); }

    /** Grava as 16 amostras (4x4) de um chunk local. Retorna true se algo mudou. */
    public boolean putChunk(int lcx, int lcz, short[] h, int[] c, byte[] f) {
        boolean changed = false;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                int idx = (lcz * 4 + j) * SIZE + lcx * 4 + i;
                int s = j * 4 + i;
                if (heights[idx] != h[s] || colors[idx] != c[s] || flags[idx] != f[s]) {
                    heights[idx] = h[s];
                    colors[idx] = c[s];
                    flags[idx] = f[s];
                    changed = true;
                }
            }
        }
        if (changed) {
            dataVersion++;
            dirtyDisk = true;
            hasData = true;
            lastUpdateMillis = System.currentTimeMillis();
        }
        return changed;
    }

    /** Preenche apenas amostras desconhecidas (dados ao vivo sempre vencem). */
    public boolean mergeUnknownFrom(Region o) {
        boolean changed = false;
        for (int i = 0; i < heights.length; i++) {
            if (heights[i] == UNKNOWN && o.heights[i] != UNKNOWN) {
                heights[i] = o.heights[i];
                colors[i] = o.colors[i];
                flags[i] = o.flags[i];
                changed = true;
            }
        }
        if (changed) { dataVersion++; hasData = true; }
        return changed;
    }

    public void recomputeHasData() {
        hasData = false;
        for (short h : heights) if (h != UNKNOWN) { hasData = true; break; }
    }

    public Region copy() {
        Region r = new Region(rx, rz);
        System.arraycopy(heights, 0, r.heights, 0, heights.length);
        System.arraycopy(colors, 0, r.colors, 0, colors.length);
        System.arraycopy(flags, 0, r.flags, 0, flags.length);
        r.hasData = hasData;
        r.lastUpdateMillis = lastUpdateMillis;
        return r;
    }

    public boolean setMask(long[] m) {
        if (Arrays.equals(mask, m)) return false;
        System.arraycopy(m, 0, mask, 0, 4);
        maskVersion++;
        return true;
    }
    public long[] maskCopy() { return mask.clone(); }
    public boolean maskNonZero() { return (mask[0] | mask[1] | mask[2] | mask[3]) != 0; }
    public boolean isMasked(int lcx, int lcz) {
        int idx = lcz * 16 + lcx;
        return ((mask[idx >> 6] >>> (idx & 63)) & 1L) != 0;
    }
}
