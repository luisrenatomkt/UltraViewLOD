package com.ultraview.lod.core;

/** Escolha de nivel de detalhe com histerese (evita piscar) e parametros de neblina da transicao. */
public final class LODTransitionManager {
    public static final double[] BASE = {384.0, 768.0, 1536.0};

    public static double qualityScale(int q) {
        switch (q) {
            case 1: return 0.6;
            case 3: return 1.5;
            case 4: return 2.2;
            default: return 1.0;
        }
    }

    /** Nivel base 0..3 (0 = celulas de 4 blocos ... 3 = 32 blocos), com histerese em fracao do limiar. */
    public int chooseBase(int prev, double dist, double scale, double hyst) {
        int lvl = 0;
        for (int i = 0; i < BASE.length; i++) if (dist >= BASE[i] * scale) lvl = i + 1;
        if (prev >= 0 && prev != lvl && Math.abs(prev - lvl) == 1) {
            double boundary = BASE[Math.min(prev, lvl)] * scale;
            if (Math.abs(dist - boundary) < boundary * hyst) return prev;
        }
        return lvl;
    }

    public static float fogStart(double visualDistance) { return (float) (visualDistance * 0.5); }
    public static float fogEnd(double visualDistance) { return (float) visualDistance; }

    /** Resolucao da textura da regiao conforme nivel (16/32/64 na base, reduz com a distancia). */
    public static int textureSizeFor(int level, int configured, boolean saver) {
        int base = 16;
        while (base * 2 <= configured && base < 64) base *= 2;
        if (saver) base = Math.min(base, 32);
        int s = base >> Math.min(level, 2);
        return Math.max(8, s);
    }
}
