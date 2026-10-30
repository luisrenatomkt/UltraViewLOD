package com.ultraview.lod.core;

import com.ultraview.lod.config.LODConfig;
import com.ultraview.lod.data.ChunkDataProvider;
import com.ultraview.lod.data.DiskCacheManager;
import com.ultraview.lod.data.Region;
import com.ultraview.lod.data.SaveReader;
import com.ultraview.lod.mesh.MeshData;
import com.ultraview.lod.mesh.TerrainMeshBuilder;
import com.ultraview.lod.occlusion.HeightSource;
import com.ultraview.lod.render.DistantTerrainRenderer;
import com.ultraview.lod.render.GLMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Coordena regioes, niveis de detalhe, cache, tarefas em segundo plano e uploads para a GPU. */
public final class LODManager implements HeightSource {
    private static final class LoadResult {
        final int gen; final long key; final Region region; final boolean fromDisk;
        LoadResult(int gen, long key, Region region, boolean fromDisk) { this.gen = gen; this.key = key; this.region = region; this.fromDisk = fromDisk; }
    }
    private static final class BuildResult {
        final int gen; final long key; final MeshData mesh; final int level, dv, mv;
        BuildResult(int gen, long key, MeshData mesh, int level, int dv, int mv) { this.gen = gen; this.key = key; this.mesh = mesh; this.level = level; this.dv = dv; this.mv = mv; }
    }

    private final Minecraft mc = Minecraft.getMinecraft();
    public final LODProfiler profiler = new LODProfiler();
    private final LODTransitionManager transition = new LODTransitionManager();
    private final BackgroundTaskManager tasks;
    private final DistantRepresentationCache cache = new DistantRepresentationCache();
    private final DiskCacheManager disk;
    public final DistantTerrainRenderer renderer;

    private final ConcurrentHashMap<Long, Region> regions = new ConcurrentHashMap<Long, Region>();
    private final ConcurrentLinkedQueue<LoadResult> loadResults = new ConcurrentLinkedQueue<LoadResult>();
    private final ConcurrentLinkedQueue<BuildResult> buildResults = new ConcurrentLinkedQueue<BuildResult>();
    private final Set<Long> diskChecked = new HashSet<Long>();
    private final Set<Long> lastMasked = new HashSet<Long>();
    private final ArrayDeque<Long> urgent = new ArrayDeque<Long>();
    private volatile Set<Long> wanted = Collections.emptySet();
    private long[] wantedKeys = new long[0];
    private double[] wantedDist = new double[0];
    private boolean[] wantedInView = new boolean[0];

    private volatile int generation;
    private World lastWorld;
    private int lastDim = Integer.MIN_VALUE;
    private volatile String worldId = "none";
    private volatile int dim;
    private volatile long tickCounter;
    private boolean active;
    private int levelBias;
    private long lastBiasChange;
    private int scanRadius = -1, scanCursor, nearCursor;
    private int[] offX = new int[0], offZ = new int[0];
    private long lastSaveTick, lastCleanTick;
    private volatile String status = "inativo";

    public LODManager() {
        tasks = new BackgroundTaskManager(LODConfig.workers, LODConfig.maxPendingTasks);
        disk = new DiskCacheManager(new File(mc.mcDataDir, "ultraviewlod/cache"));
        renderer = new DistantTerrainRenderer(this);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() { saveAllSync(); }
        }, "UVLOD-Shutdown"));
    }

    // ---------------------------------------------------------------- acesso
    public boolean isActive() { return active; }
    public long tick() { return tickCounter; }
    public DistantRepresentationCache cache() { return cache; }
    public long[] wantedKeys() { return wantedKeys; }
    public String worldId() { return worldId; }

    public Region getOrCreateRegion(int rx, int rz) {
        long k = Region.key(rx, rz);
        Region r = regions.get(k);
        if (r == null) { r = new Region(rx, rz); regions.put(k, r); }
        return r;
    }

    public void markUrgentBlock(BlockPos p) {
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (urgent.size() < 64) urgent.add(pack(cx + dx, cz + dz));
    }
    private static long pack(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    // ---------------------------------------------------------------- HeightSource (thread principal)
    public int surfaceHeight(int bx, int bz) {
        Region r = regions.get(Region.key(bx >> 8, bz >> 8));
        if (r == null) return HeightSource.UNKNOWN;
        short h = r.heights[((bz & 255) >> 2) * Region.SIZE + ((bx & 255) >> 2)];
        return h == Region.UNKNOWN ? HeightSource.UNKNOWN : h;
    }
    public boolean isRealChunkLoaded(int bx, int bz) {
        World w = mc.theWorld;
        if (w == null) return false;
        return ChunkDataProvider.isLive(w.getChunkFromChunkCoords(bx >> 4, bz >> 4));
    }

    // ---------------------------------------------------------------- tick
    public void onClientTick() {
        tickCounter++;
        World w = mc.theWorld;
        if (!LODConfig.enabled || !LODConfig.distantRendering || w == null || mc.thePlayer == null) {
            if (active) { active = false; status = "desativado"; }
            return;
        }
        long t0 = System.nanoTime();
        if (w != lastWorld || w.provider.getDimensionId() != lastDim) switchWorld(w);
        if (w.provider.getHasNoSky()) { active = false; status = "dimensao sem ceu (nao suportada)"; return; }
        active = true;
        status = "ativo";

        int pcx = MathHelper.floor_double(mc.thePlayer.posX) >> 4, pcz = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        if (mc.thePlayer.isSwingInProgress && mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            markUrgentBlock(mc.objectMouseOver.getBlockPos());
        }
        scanChunks(w, pcx, pcz);
        processLoadResults();
        if (tickCounter % 10 == 0) { rebuildWantedList(); updateMasks(w, pcx, pcz); }
        driveRegions();
        if (tickCounter - lastCleanTick >= 40) { lastCleanTick = tickCounter; enforceMemory(pcx, pcz); }
        if (tickCounter - lastSaveTick >= 1200) { lastSaveTick = tickCounter; saveDirtyAsync(); }
        profiler.recordTick(System.nanoTime() - t0);
    }

    private void switchWorld(World w) {
        saveDirtyAsync();
        generation++;
        regions.clear();
        cache.clear();
        loadResults.clear();
        buildResults.clear();
        diskChecked.clear();
        lastMasked.clear();
        urgent.clear();
        wanted = Collections.emptySet();
        wantedKeys = new long[0]; wantedDist = new double[0]; wantedInView = new boolean[0];
        levelBias = 0;
        lastWorld = w;
        lastDim = w.provider.getDimensionId();
        dim = lastDim;
        worldId = computeWorldId();
        profiler.reset();
        if (LODConfig.conventionalRenderDistance > 0) {
            mc.gameSettings.setOptionFloatValue(GameSettings.Options.RENDER_DISTANCE, LODConfig.conventionalRenderDistance);
        }
        if (LODConfig.cacheEnabled) tasks.submit(1000000, new Runnable() {
            final String id = worldId;
            public void run() { disk.enforceLimit(id, LODConfig.diskLimitMB * 1024L * 1024L); }
        });
    }

    private String computeWorldId() {
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) {
            IntegratedServer s = mc.getIntegratedServer();
            long seed = 0;
            try { seed = s.getEntityWorld().getSeed(); } catch (Throwable ignored) { }
            return "sp_" + s.getFolderName() + "_" + Long.toHexString(seed);
        }
        ServerData sd = mc.getCurrentServerData();
        return "mp_" + (sd != null && sd.serverIP != null ? sd.serverIP.toLowerCase() : "desconhecido");
    }

    /** Pasta da dimensao no save (somente singleplayer) ou null. */
    private File saveDimDir() {
        try {
            if (!mc.isSingleplayer() || mc.getIntegratedServer() == null) return null;
            File root = mc.getIntegratedServer().getEntityWorld().getSaveHandler().getWorldDirectory();
            String sub = mc.theWorld.provider.getSaveFolder();
            return sub == null ? root : new File(root, sub);
        } catch (Throwable t) { return null; }
    }

    // ---------------------------------------------------------------- varredura de chunks
    private void buildScanOffsets(int rd) {
        scanRadius = rd;
        List<int[]> l = new ArrayList<int[]>();
        for (int dx = -rd; dx <= rd; dx++) for (int dz = -rd; dz <= rd; dz++) l.add(new int[]{dx, dz});
        Collections.sort(l, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]); }
        });
        offX = new int[l.size()]; offZ = new int[l.size()];
        for (int i = 0; i < l.size(); i++) { offX[i] = l.get(i)[0]; offZ[i] = l.get(i)[1]; }
        scanCursor = 0;
    }

    private void scanChunks(World w, int pcx, int pcz) {
        int rd = Math.max(2, mc.gameSettings.renderDistanceChunks);
        if (rd != scanRadius) buildScanOffsets(rd);
        int budget = LODConfig.scanChunksPerTick;
        if (LODConfig.memorySaver) budget = Math.max(1, budget / 2);
        int done = 0;
        Long u;
        while (done < budget && (u = urgent.pollFirst()) != null) { scanOne(w, (int) (u >> 32), (int) (long) u); done++; }
        int nd = nearCursor % 25;
        scanOne(w, pcx + (nd % 5) - 2, pcz + (nd / 5) - 2);
        nearCursor++;
        for (; done < budget; done++) {
            scanOne(w, pcx + offX[scanCursor], pcz + offZ[scanCursor]);
            if (++scanCursor >= offX.length) scanCursor = 0;
        }
    }

    private void scanOne(World w, int cx, int cz) {
        if (ChunkDataProvider.scanChunk(w, cx, cz, this)) profiler.chunksScanned.incrementAndGet();
    }

    // ---------------------------------------------------------------- mascara (chunks que o Minecraft desenha)
    private void updateMasks(World w, int pcx, int pcz) {
        int rd = Math.max(2, mc.gameSettings.renderDistanceChunks);
        Set<Long> now = new HashSet<Long>();
        for (int rx = (pcx - rd) >> 4; rx <= (pcx + rd) >> 4; rx++) {
            for (int rz = (pcz - rd) >> 4; rz <= (pcz + rd) >> 4; rz++) {
                long k = Region.key(rx, rz);
                Region r = regions.get(k);
                if (r == null) continue;
                long[] m = new long[4];
                for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                    int cx = rx * 16 + lx, cz = rz * 16 + lz;
                    if (Math.abs(cx - pcx) <= rd && Math.abs(cz - pcz) <= rd && ChunkDataProvider.isLive(w.getChunkFromChunkCoords(cx, cz))) {
                        int idx = lz * 16 + lx;
                        m[idx >> 6] |= 1L << (idx & 63);
                    }
                }
                r.setMask(m);
                now.add(k);
            }
        }
        for (Long k : lastMasked) {
            if (!now.contains(k)) { Region r = regions.get(k); if (r != null) r.setMask(new long[4]); }
        }
        lastMasked.clear();
        lastMasked.addAll(now);
    }

    // ---------------------------------------------------------------- lista de regioes desejadas
    private static final class Cand { long key; double dist; boolean inView; int prio; }

    private void rebuildWantedList() {
        double px = mc.thePlayer.posX, pz = mc.thePlayer.posZ;
        double dist = LODConfig.visualDistance;
        double yaw = mc.thePlayer.rotationYaw * 0.017453292;
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        List<Cand> list = new ArrayList<Cand>();
        for (int rx = MathHelper.floor_double((px - dist) / 256) - 1; rx <= MathHelper.floor_double((px + dist) / 256) + 1; rx++) {
            for (int rz = MathHelper.floor_double((pz - dist) / 256) - 1; rz <= MathHelper.floor_double((pz + dist) / 256) + 1; rz++) {
                double dx = rx * 256.0 + 128 - px, dz = rz * 256.0 + 128 - pz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > dist + 181) continue;
                Cand c = new Cand();
                c.key = Region.key(rx, rz);
                c.dist = d;
                c.inView = d < 300 || (dx * fx + dz * fz) / d > 0.2;
                switch (LODConfig.loadPriority) {
                    case 0: c.prio = (int) d; break;
                    case 2: c.prio = c.inView ? (int) d : (int) (d * 2 + 500); break;
                    default: c.prio = c.inView ? (int) d : (int) d + 4000;
                }
                list.add(c);
            }
        }
        Collections.sort(list, new Comparator<Cand>() {
            public int compare(Cand a, Cand b) { return Integer.compare(a.prio, b.prio); }
        });
        int n = list.size();
        long[] keys = new long[n]; double[] ds = new double[n]; boolean[] iv = new boolean[n];
        Set<Long> want = new HashSet<Long>();
        for (int i = 0; i < n; i++) { keys[i] = list.get(i).key; ds[i] = list.get(i).dist; iv[i] = list.get(i).inView; want.add(keys[i]); }
        wanted = want;
        wantedKeys = keys; wantedDist = ds; wantedInView = iv;
    }

    // ---------------------------------------------------------------- carregamento e malhas
    private void driveRegions() {
        int meshBudget = LODConfig.regionsPerCycle;
        if (LODConfig.memorySaver) meshBudget = Math.max(1, meshBudget / 2);
        int loadBudget = 2;
        double scale = LODTransitionManager.qualityScale(LODConfig.lodQuality);
        double hyst = LODConfig.transitionHysteresis / 100.0;
        long[] keys = wantedKeys;
        for (int i = 0; i < keys.length && (meshBudget > 0 || loadBudget > 0); i++) {
            long key = keys[i];
            if (!wantedInView[i] && !LODConfig.prefetch) continue;
            int prio = i;
            if (!diskChecked.contains(key) && loadBudget > 0) {
                if (requestLoad(key, prio)) loadBudget--;
            }
            Region r = regions.get(key);
            if (r == null || r.building || !r.hasData || meshBudget <= 0) continue;
            r.levelBase = transition.chooseBase(r.levelBase, wantedDist[i], scale, hyst);
            int level = Math.min(3, r.levelBase + levelBias);
            boolean stale = r.builtLevel != level || r.builtDataVersion != r.dataVersion || r.builtMaskVersion != r.maskVersion;
            if (!stale) continue;
            if (r.builtLevel == level && tickCounter - r.lastBuildTick < 20) continue; // limita reconstrucoes da mesma regiao
            if (submitBuild(r, level, prio)) meshBudget--;
        }
    }

    private boolean requestLoad(final long key, int prio) {
        final boolean useDisk = LODConfig.cacheEnabled;
        final File dimDir = LODConfig.prefetchFromSave ? saveDimDir() : null;
        if (!useDisk && dimDir == null) { diskChecked.add(key); return false; }
        final int gen = generation, d = dim;
        final String id = worldId;
        final long maxAge = LODConfig.cacheMaxAgeHours * 3600000L;
        final boolean veg = LODConfig.vegetationDetail > 0;
        final int rx = Region.keyX(key), rz = Region.keyZ(key);
        boolean ok = tasks.submit(prio + 100000, new Runnable() {
            public void run() {
                if (gen != generation) return;
                Region r = null;
                boolean fromDisk = false;
                if (useDisk) { r = disk.load(id, d, rx, rz, maxAge); fromDisk = r != null; }
                if (r == null && dimDir != null) r = SaveReader.read(dimDir, rx, rz, veg);
                loadResults.add(new LoadResult(gen, key, r, fromDisk));
            }
        });
        if (ok) diskChecked.add(key);
        return ok;
    }

    private void processLoadResults() {
        LoadResult lr;
        int n = 0;
        while (n++ < 4 && (lr = loadResults.poll()) != null) {
            if (lr.gen != generation || lr.region == null) continue;
            Region ex = regions.get(lr.key);
            if (ex == null) {
                lr.region.dirtyDisk = !lr.fromDisk && LODConfig.cacheEnabled;
                regions.put(lr.key, lr.region);
            } else if (ex.mergeUnknownFrom(lr.region) && !lr.fromDisk) {
                ex.dirtyDisk = LODConfig.cacheEnabled;
            }
            if (lr.fromDisk) profiler.diskHits.incrementAndGet(); else profiler.saveReads.incrementAndGet();
        }
    }

    private short[][] neighborEdges(Region r) {
        short[][] e = new short[4][];
        Region w = regions.get(Region.key(r.rx - 1, r.rz));
        Region ea = regions.get(Region.key(r.rx + 1, r.rz));
        Region no = regions.get(Region.key(r.rx, r.rz - 1));
        Region so = regions.get(Region.key(r.rx, r.rz + 1));
        int S = Region.SIZE;
        if (w != null) { e[0] = new short[S]; for (int i = 0; i < S; i++) e[0][i] = w.heights[i * S + S - 1]; }
        if (ea != null) { e[1] = new short[S]; for (int i = 0; i < S; i++) e[1][i] = ea.heights[i * S]; }
        if (no != null) { e[2] = new short[S]; for (int i = 0; i < S; i++) e[2][i] = no.heights[(S - 1) * S + i]; }
        if (so != null) { e[3] = new short[S]; for (int i = 0; i < S; i++) e[3][i] = so.heights[i]; }
        return e;
    }

    private boolean submitBuild(Region r, final int level, int prio) {
        final int gen = generation, dv = r.dataVersion, mv = r.maskVersion;
        final long key = r.key();
        final short[] h = r.heights.clone();
        final int[] c = r.colors.clone();
        final long[] mask = r.maskCopy();
        final short[][] edges = neighborEdges(r);
        final int tex = LODTransitionManager.textureSizeFor(level, LODConfig.textureResolution, LODConfig.memorySaver);
        r.building = true;
        boolean ok = tasks.submit(prio, new Runnable() {
            public void run() {
                if (gen != generation || !wanted.contains(key)) { buildResults.add(new BuildResult(gen, key, null, level, dv, mv)); return; }
                MeshData m = null;
                try {
                    long t0 = System.nanoTime();
                    m = TerrainMeshBuilder.build(h, c, level, mask, edges, tex);
                    profiler.recordMesh(System.nanoTime() - t0);
                } catch (Throwable t) {
                    System.err.println("[UltraViewLOD] falha ao gerar malha: " + t);
                }
                buildResults.add(new BuildResult(gen, key, m, level, dv, mv));
            }
        });
        if (!ok) r.building = false;
        return ok;
    }

    /** Chamado pelo renderizador (thread principal, contexto OpenGL valido). */
    public void processUploads(long budgetNanos) {
        long t0 = System.nanoTime();
        BuildResult br;
        boolean first = true;
        while ((first || System.nanoTime() - t0 < budgetNanos) && (br = buildResults.poll()) != null) {
            first = false;
            if (br.gen != generation) continue;
            Region r = regions.get(br.key);
            if (r == null) continue;
            r.building = false;
            if (br.mesh == null) continue;
            GLMesh m = renderer.upload(br.mesh);
            m.lastUse = tickCounter;
            cache.put(br.key, m);
            r.builtLevel = br.level; r.builtDataVersion = br.dv; r.builtMaskVersion = br.mv; r.lastBuildTick = tickCounter;
            profiler.uploads.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- memoria e disco
    private long regionBytes() { return (long) regions.size() * Region.BYTES; }

    private void enforceMemory(int pcx, int pcz) {
        long limit = LODConfig.cacheMemoryMB * 1024L * 1024L;
        long meshBudget = (long) (limit * 0.7), dataBudget = limit - meshBudget;
        // 1) malhas: LRU, preservando o que foi desenhado recentemente
        cache.evictTo(meshBudget, tickCounter - 40);
        if (cache.bytes() > meshBudget && tickCounter - lastBiasChange > 100 && levelBias < 3) { levelBias++; lastBiasChange = tickCounter; }
        else if (cache.bytes() < meshBudget * 0.5 && levelBias > 0 && tickCounter - lastBiasChange > 400) { levelBias--; lastBiasChange = tickCounter; }
        // 2) dados: remove regioes fora do alcance e, se preciso, as mais distantes (salvando antes)
        double px = mc.thePlayer.posX, pz = mc.thePlayer.posZ;
        double maxD = LODConfig.visualDistance + 512;
        List<Region> list = new ArrayList<Region>(regions.values());
        boolean over = regionBytes() > dataBudget;
        if (!over) {
            boolean any = false;
            for (Region r : list) if (farFrom(r, px, pz) > maxD) { any = true; break; }
            if (!any) return;
        }
        Collections.sort(list, new Comparator<Region>() {
            public int compare(Region a, Region b) { return Double.compare(farFrom(b, mc.thePlayer.posX, mc.thePlayer.posZ), farFrom(a, mc.thePlayer.posX, mc.thePlayer.posZ)); }
        });
        for (Region r : list) {
            double d = farFrom(r, px, pz);
            boolean beyond = d > maxD;
            if (!beyond && regionBytes() <= dataBudget) break;
            if (d < 400) continue;
            if (r.dirtyDisk && LODConfig.cacheEnabled) { r.dirtyDisk = false; saveAsync(r); }
            regions.remove(r.key());
            cache.remove(r.key());
            diskChecked.remove(r.key());
        }
    }

    private static double farFrom(Region r, double px, double pz) {
        double dx = r.rx * 256.0 + 128 - px, dz = r.rz * 256.0 + 128 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void saveAsync(Region r) {
        if (!LODConfig.cacheEnabled) return;
        final Region copy = r.copy();
        final String id = worldId; final int d = dim;
        tasks.submit(900000, new Runnable() { public void run() { disk.save(id, d, copy); } });
    }

    private void saveDirtyAsync() {
        if (!LODConfig.cacheEnabled) return;
        for (Region r : regions.values()) if (r.dirtyDisk) { r.dirtyDisk = false; saveAsync(r); }
        final String id = worldId;
        tasks.submit(1000000, new Runnable() { public void run() { disk.enforceLimit(id, LODConfig.diskLimitMB * 1024L * 1024L); } });
    }

    private void saveAllSync() {
        if (!LODConfig.cacheEnabled) return;
        try {
            for (Region r : regions.values()) if (r.dirtyDisk) { disk.save(worldId, dim, r.copy()); r.dirtyDisk = false; }
        } catch (Throwable ignored) { }
    }

    public void onDisconnect() { saveDirtyAsync(); lastWorld = null; }

    public int clearCache() {
        int n = disk.clearWorld(worldId);
        for (Region r : regions.values()) { r.dirtyDisk = false; }
        regions.clear();
        cache.clear();
        diskChecked.clear();
        generation++;
        return n;
    }

    public void onConfigChanged() { /* valores sao lidos a cada uso; nada a reconstruir */ }

    // ---------------------------------------------------------------- diagnostico
    public List<String> debugLines() {
        List<String> l = new ArrayList<String>();
        l.add("[UltraView LOD] " + status + " | alvo " + LODConfig.visualDistance + " blocos | bias de qualidade " + levelBias);
        l.add(String.format(Locale.ROOT, "  regioes: %d em memoria, %d com malha, %d desenhadas | tarefas pendentes: %d | carregando: %d",
            regions.size(), cache.size(), profiler.regionsDrawn, tasks.pending(), diskChecked.size() - 0));
        l.add(String.format(Locale.ROOT, "  cache disco reutilizado: %d | lidas do save: %d | malhas geradas: %d (media %.2f ms)",
            profiler.diskHits.get(), profiler.saveReads.get(), profiler.meshBuilds.get(), profiler.meshMsAvg()));
        l.add(String.format(Locale.ROOT, "  memoria estimada: dados %.1f MB + malhas %.1f MB / limite %d MB",
            regionBytes() / 1048576.0, cache.bytes() / 1048576.0, LODConfig.cacheMemoryMB));
        l.add(String.format(Locale.ROOT, "  tick %.3f ms | render %.3f ms | chunks amostrados: %d | alteracoes detectadas: %d | oclusao: ATIVA (sempre)",
            profiler.tickMsAvg, profiler.renderMsAvg, profiler.chunksScanned.get(), profiler.chunkChanges.get()));
        return l;
    }
}
