package com.ultraview.lod;

import com.ultraview.lod.data.DiskCacheManager;
import com.ultraview.lod.data.Region;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class RegionAndDiskTest {
    private static Region sample() {
        Region r = new Region(3, -2);
        short[] h = new short[16]; int[] c = new int[16]; byte[] f = new byte[16];
        for (int i = 0; i < 16; i++) { h[i] = (short) (60 + i); c[i] = 0xFF00AA00 + i; }
        assertTrue(r.putChunk(5, 7, h, c, f));
        assertFalse("mesmos dados nao devem invalidar", r.putChunk(5, 7, h, c, f));
        return r;
    }

    @Test public void putChunkBumpsVersionOnlyOnChange() {
        Region r = new Region(0, 0);
        int v = r.dataVersion;
        sample();
        assertEquals(v, r.dataVersion);
    }

    @Test public void mergeOnlyFillsUnknown() {
        Region live = new Region(0, 0), disk = new Region(0, 0);
        live.heights[0] = 70; disk.heights[0] = 10; disk.heights[1] = 20;
        assertTrue(live.mergeUnknownFrom(disk));
        assertEquals(70, live.heights[0]);
        assertEquals(20, live.heights[1]);
    }

    @Test public void roundTripAndWorldIsolation() throws Exception {
        File dir = Files.createTempDirectory("uvl").toFile();
        DiskCacheManager d = new DiskCacheManager(dir);
        Region r = sample();
        assertTrue(d.save("sp_a", 0, r));
        Region back = d.load("sp_a", 0, 3, -2, 0);
        assertNotNull(back);
        assertArrayEquals(r.heights, back.heights);
        assertArrayEquals(r.colors, back.colors);
        assertNull("outro mundo nao deve ver o cache", d.load("sp_b", 0, 3, -2, 0));
        assertNull("outra dimensao nao deve ver o cache", d.load("sp_a", -1, 3, -2, 0));
    }

    @Test public void corruptFileIsDiscarded() throws Exception {
        File dir = Files.createTempDirectory("uvl").toFile();
        DiskCacheManager d = new DiskCacheManager(dir);
        d.save("w", 0, sample());
        File f = new File(d.dimDir("w", 0), "r.3.-2.uvl");
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.seek(f.length() / 2); raf.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        raf.close();
        assertNull(d.load("w", 0, 3, -2, 0));
        assertFalse(f.exists());
    }

    @Test public void limitOnlyAffectsOneWorldAndClearIsScoped() throws Exception {
        File dir = Files.createTempDirectory("uvl").toFile();
        DiskCacheManager d = new DiskCacheManager(dir);
        d.save("a", 0, new Region(1, 1)); d.save("b", 0, new Region(1, 1));
        d.enforceLimit("a", 0);
        assertEquals(0, d.sizeBytes("a"));
        assertTrue(d.sizeBytes("b") > 0);
        assertEquals(1, d.clearWorld("b"));
        assertEquals(0, d.sizeBytes("b"));
    }
}
