package com.ultraview.lod.data;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.BlockPos;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;

import java.io.DataInputStream;
import java.io.File;

/**
 * Singleplayer: le (SOMENTE LEITURA, em segundo plano) chunks ja gerados dos arquivos .mca para preparar o LOD.
 * So abre arquivos que ja existem; nunca cria nem grava nada no mundo.
 */
public final class SaveReader {
    private SaveReader() { }

    public static Region read(File dimDir, int rx, int rz, boolean vegetation) {
        int cx0 = rx * 16, cz0 = rz * 16;
        File mca = new File(new File(dimDir, "region"), "r." + (cx0 >> 5) + "." + (cz0 >> 5) + ".mca");
        if (!mca.isFile()) return null;
        Region region = new Region(rx, rz);
        short[] h = new short[16];
        int[] c = new int[16];
        byte[] f = new byte[16];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int cx = cx0 + lx, cz = cz0 + lz;
                try {
                    RegionFile rf = RegionFileCache.createOrLoadRegionFile(dimDir, cx, cz);
                    DataInputStream in = rf.getChunkDataInputStream(cx & 31, cz & 31);
                    if (in == null) continue;
                    NBTTagCompound root;
                    try { root = CompressedStreamTools.read(in); } finally { in.close(); }
                    final NBTTagCompound level = root.getCompoundTag("Level");
                    final int wx0 = cx << 4, wz0 = cz << 4;
                    final byte[][] blocks = new byte[16][], add = new byte[16][], data = new byte[16][];
                    NBTTagList secs = level.getTagList("Sections", 10);
                    for (int i = 0; i < secs.tagCount(); i++) {
                        NBTTagCompound s = secs.getCompoundTagAt(i);
                        int y = s.getByte("Y") & 15;
                        blocks[y] = s.getByteArray("Blocks");
                        data[y] = s.getByteArray("Data");
                        if (s.hasKey("Add", 7)) add[y] = s.getByteArray("Add");
                    }
                    final int[] hm = level.getIntArray("HeightMap");
                    final byte[] biomes = level.getByteArray("Biomes");
                    if (hm.length < 256) continue;
                    boolean ok = ChunkDataProvider.sampleChunk(hm, new ChunkDataProvider.BlockReaderFactory() {
                        public ChunkDataProvider.BlockReader reader(final int x, final int z) {
                            return new ChunkDataProvider.BlockReader() {
                                public IBlockState get(int y) { return state(blocks, add, data, x, y, z); }
                                public int tint(Block b, int y) { return biomeTint(b, biomes, x, z, wx0 + x, y, wz0 + z); }
                            };
                        }
                    }, vegetation, h, c, f);
                    if (ok) region.putChunk(lx, lz, h, c, f);
                } catch (Throwable t) {
                    // chunk ilegivel: simplesmente fica desconhecido
                }
            }
        }
        region.recomputeHasData();
        region.dirtyDisk = false;
        return region.hasData ? region : null;
    }

    private static int nib(byte[] a, int idx) {
        int v = a[idx >> 1];
        return (idx & 1) == 0 ? v & 15 : (v >> 4) & 15;
    }

    private static IBlockState state(byte[][] blocks, byte[][] add, byte[][] data, int x, int y, int z) {
        int sy = y >> 4;
        if (sy < 0 || sy > 15 || blocks[sy] == null || blocks[sy].length < 4096) return Blocks.air.getDefaultState();
        int idx = ((y & 15) << 8) | (z << 4) | x;
        int id = blocks[sy][idx] & 255;
        if (add[sy] != null && add[sy].length >= 2048) id |= nib(add[sy], idx) << 8;
        if (id == 0) return Blocks.air.getDefaultState();
        Block b = Block.getBlockById(id);
        if (b == null) return Blocks.air.getDefaultState();
        int meta = (data[sy] != null && data[sy].length >= 2048) ? nib(data[sy], idx) : 0;
        try { return b.getStateFromMeta(meta); } catch (Throwable t) { return b.getDefaultState(); }
    }

    private static int biomeTint(Block b, byte[] biomes, int lx, int lz, int wx, int y, int wz) {
        boolean grass = b == Blocks.grass;
        boolean leaves = b instanceof net.minecraft.block.BlockLeaves;
        boolean water = b == Blocks.water || b == Blocks.flowing_water;
        if (!grass && !leaves && !water) return -1;
        BiomeGenBase bg = null;
        try {
            if (biomes != null && biomes.length >= 256) bg = BiomeGenBase.getBiome(biomes[lz << 4 | lx] & 255);
        } catch (Throwable ignored) { }
        if (bg == null) bg = BiomeGenBase.plains;
        BlockPos pos = new BlockPos(wx, y, wz);
        if (grass) return bg.getGrassColorAtPos(pos);
        if (leaves) return bg.getFoliageColorAtPos(pos);
        return bg.waterColorMultiplier;
    }
}
