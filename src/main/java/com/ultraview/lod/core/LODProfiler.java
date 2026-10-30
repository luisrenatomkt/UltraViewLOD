package com.ultraview.lod.core;

import java.util.concurrent.atomic.AtomicLong;

/** Metricas reais (contadores e medias moveis). Custo desprezivel. */
public final class LODProfiler {
    public final AtomicLong diskHits = new AtomicLong();
    public final AtomicLong saveReads = new AtomicLong();
    public final AtomicLong meshBuilds = new AtomicLong();
    public final AtomicLong meshNanos = new AtomicLong();
    public final AtomicLong uploads = new AtomicLong();
    public final AtomicLong chunksScanned = new AtomicLong();
    public final AtomicLong chunkChanges = new AtomicLong();
    public volatile double tickMsAvg;
    public volatile double renderMsAvg;
    public volatile int regionsDrawn;

    public void recordTick(long nanos) { tickMsAvg = tickMsAvg * 0.95 + (nanos / 1e6) * 0.05; }
    public void recordRender(long nanos) { renderMsAvg = renderMsAvg * 0.95 + (nanos / 1e6) * 0.05; }
    public void recordMesh(long nanos) { meshBuilds.incrementAndGet(); meshNanos.addAndGet(nanos); }
    public double meshMsAvg() { long n = meshBuilds.get(); return n == 0 ? 0 : meshNanos.get() / 1e6 / n; }
    public void reset() {
        diskHits.set(0); saveReads.set(0); meshBuilds.set(0); meshNanos.set(0); uploads.set(0);
        chunksScanned.set(0); chunkChanges.set(0);
    }
}
