package com.ultraview.lod;

import com.ultraview.lod.core.BackgroundTaskManager;
import com.ultraview.lod.core.DistantRepresentationCache;
import com.ultraview.lod.core.LODTransitionManager;
import com.ultraview.lod.data.Region;
import com.ultraview.lod.mesh.MeshData;
import com.ultraview.lod.mesh.TerrainMeshBuilder;
import com.ultraview.lod.occlusion.HeightSource;
import com.ultraview.lod.occlusion.OcclusionManager;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.*;

public class MeshAndLogicTest {
    private static short[] flat(int h) { short[] a = new short[Region.SIZE * Region.SIZE]; Arrays.fill(a, (short) h); return a; }
    private static byte[] nof() { return new byte[Region.SIZE * Region.SIZE]; }
    private static int[] green() { int[] c = new int[Region.SIZE * Region.SIZE]; Arrays.fill(c, 0xFF40A040); return c; }

    @Test public void flatTerrainHasOneQuadPerCell() {
        MeshData m = TerrainMeshBuilder.build(flat(64), nof(), green(), 3, new long[4], null, 16);
        assertEquals(8 * 8 * 4, m.vertCount);
        assertEquals(16 * 16 * 4, m.texRgba.length);
    }

    @Test public void unknownCellsProduceNoGeometry() {
        short[] h = new short[Region.SIZE * Region.SIZE]; Arrays.fill(h, Region.UNKNOWN);
        assertEquals(0, TerrainMeshBuilder.build(h, nof(), new int[h.length], 0, new long[4], null, 64).vertCount);
    }

    @Test public void maskedChunksAreSkipped() {
        long[] mask = {-1L, -1L, -1L, -1L}; // todos os chunks ja sao desenhados pelo Minecraft
        assertEquals(0, TerrainMeshBuilder.build(flat(64), nof(), green(), 0, mask, null, 64).vertCount);
    }

    @Test public void cliffGetsWallOnTheHigherSide() {
        short[] h = flat(60);
        for (int z = 0; z < Region.SIZE; z++) for (int x = 0; x < 32; x++) h[z * Region.SIZE + x] = 80;
        MeshData m = TerrainMeshBuilder.build(h, nof(), green(), 3, new long[4], null, 16);
        assertTrue(m.vertCount > 8 * 8 * 4); // existem paredes
    }

    /** Regressao do bug do print: copas de arvore viravam relevo (paredes verdes gigantes). */
    @Test public void canopyIsNotUsedAsTerrainHeight() {
        short[] h = flat(90);                       // topo (copa) a 90
        byte[] f = new byte[Region.SIZE * Region.SIZE];
        java.util.Arrays.fill(f, (byte) (Region.DROP_MAX << Region.DROP_SHIFT)); // queda 63 => chao 27 (cap)
        for (int i = 0; i < f.length; i++) f[i] = (byte) (26 << Region.DROP_SHIFT); // chao a 64
        MeshData m = TerrainMeshBuilder.build(h, f, green(), 3, new long[4], null, 16);
        assertEquals(8 * 8 * 4, m.vertCount);
        for (int i = 0; i < m.vertCount; i++) assertEquals(64f, m.verts[i * 6 + 1], 0.001f);
    }

    @Test public void cellHeightIsMeanNotMax() {
        short[] h = flat(60);
        h[0] = 80; // um pico isolado dentro da celula 8x8 (2x2 amostras) nao deve inflar a celula inteira
        MeshData m = TerrainMeshBuilder.build(h, nof(), green(), 1, new long[4], null, 32);
        float y0 = m.verts[1];
        assertEquals(65f, y0, 0.001f); // (80 + 3*60) / 4
    }

    @Test public void regionBorderSkirtHidesCracks() {
        int[][] edges = new int[4][];
        edges[1] = new int[Region.SIZE]; java.util.Arrays.fill(edges[1], 60); // vizinho leste mais baixo
        MeshData withEdge = TerrainMeshBuilder.build(flat(64), nof(), green(), 3, new long[4], edges, 16);
        MeshData none = TerrainMeshBuilder.build(flat(64), nof(), green(), 3, new long[4], null, 16);
        assertTrue(withEdge.vertCount > none.vertCount);
    }

    @Test public void hysteresisAvoidsFlicker() {
        LODTransitionManager t = new LODTransitionManager();
        assertEquals(0, t.chooseBase(-1, 300, 1.0, 0.1));
        assertEquals(1, t.chooseBase(-1, 400, 1.0, 0.1));
        assertEquals(0, t.chooseBase(0, 400, 1.0, 0.1));  // dentro da faixa: mantem
        assertEquals(1, t.chooseBase(0, 450, 1.0, 0.1));  // saiu da faixa: troca
    }

    @Test public void textureSizeShrinksWithDistance() {
        assertEquals(64, LODTransitionManager.textureSizeFor(0, 64, false));
        assertEquals(32, LODTransitionManager.textureSizeFor(1, 64, false));
        assertEquals(16, LODTransitionManager.textureSizeFor(3, 64, false));
        assertEquals(32, LODTransitionManager.textureSizeFor(0, 64, true));
    }

    private static class H implements DistantRepresentationCache.Handle {
        final long b, use; boolean released;
        H(long b, long use) { this.b = b; this.use = use; }
        public long bytes() { return b; } public void release() { released = true; } public long lastUse() { return use; }
    }

    @Test public void lruEvictsOldestUnprotected() {
        DistantRepresentationCache c = new DistantRepresentationCache();
        H a = new H(100, 1), b = new H(100, 2), d = new H(100, 50);
        c.put(1, a); c.put(2, b); c.put(3, d);
        c.get(1); // a vira o mais recente na ordem de acesso
        int n = c.evictTo(100, 10);
        assertEquals(1, n);
        assertTrue(b.released);
        assertEquals(200, c.bytes());
    }

    private static class Hills implements HeightSource {
        final int ridgeX; final boolean knownAround;
        Hills(int ridgeX, boolean knownAround) { this.ridgeX = ridgeX; this.knownAround = knownAround; }
        public int surfaceHeight(int x, int z) {
            if (Math.abs(x - ridgeX) < 6) return 120;
            return knownAround ? 64 : HeightSource.UNKNOWN;
        }
        public boolean isRealChunkLoaded(int x, int z) { return false; }
    }

    @Test public void mountainBlocksPlayerBehindIt() {
        assertTrue(OcclusionManager.lodBlocks(new Hills(100, true), 0, 70, 0, 200, 70, 0, 2, 1.5));
    }
    @Test public void clearTerrainDoesNotBlock() {
        assertFalse(OcclusionManager.lodBlocks(new Hills(1000, true), 0, 70, 0, 200, 70, 0, 2, 1.5));
    }
    @Test public void unknownTerrainIsConservative() {
        assertTrue(OcclusionManager.lodBlocks(new Hills(1000, false), 0, 70, 0, 200, 70, 0, 2, 1.5));
    }
    @Test public void realLoadedChunksAreLeftToWorldRaycast() {
        HeightSource s = new HeightSource() {
            public int surfaceHeight(int x, int z) { return 200; }
            public boolean isRealChunkLoaded(int x, int z) { return true; }
        };
        assertFalse(OcclusionManager.lodBlocks(s, 0, 70, 0, 200, 70, 0, 2, 1.5));
    }

    @Test public void taskQueueIsBounded() throws Exception {
        BackgroundTaskManager m = new BackgroundTaskManager(1, 3);
        final CountDownLatch gate = new CountDownLatch(1);
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (m.submit(i, new Runnable() { public void run() { try { gate.await(); } catch (InterruptedException ignored) { } } })) accepted++;
        }
        assertEquals(3, accepted);
        gate.countDown();
        m.shutdown(1000);
    }
}
