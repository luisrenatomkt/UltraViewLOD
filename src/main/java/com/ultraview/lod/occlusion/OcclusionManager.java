package com.ultraview.lod.occlusion;

/**
 * Teste de linha de visao contra a geometria simplificada do LOD. Politica CONSERVADORA:
 * trecho sobre area sem chunk real e sem dado de LOD conta como bloqueado.
 */
public final class OcclusionManager {
    private OcclusionManager() { }

    /** True se o raio (origem -> alvo) atravessa o terreno do LOD. */
    public static boolean lodBlocks(HeightSource src, double x0, double y0, double z0,
                                    double x1, double y1, double z1, double step, double endMargin) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= endMargin + step) return false;
        int steps = (int) Math.ceil(dist / step);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double d = t * dist;
            if (d < 2.0) continue;
            if (dist - d < endMargin) break;
            double px = x0 + dx * t, py = y0 + dy * t, pz = z0 + dz * t;
            int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
            if (src.isRealChunkLoaded(bx, bz)) continue;
            int h = src.surfaceHeight(bx, bz);
            if (h == HeightSource.UNKNOWN) return true;
            if (py < h) return true;
        }
        return false;
    }
}
