package com.ultraview.lod.data;

import com.ultraview.lod.config.LODConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Obtem dados de terreno JA disponiveis no cliente (chunks recebidos) e os resume em 4x4 amostras por chunk.
 * Nunca inventa dados: chunk vazio/nao recebido => nada e gravado.
 */
public final class ChunkDataProvider {
    private ChunkDataProvider() { }

    /** Leitor de colunas (implementado para chunk vivo e para NBT do save). */
    public interface BlockReader {
        IBlockState get(int y);
        /** Cor de tint (bioma) ou -1 / 0xFFFFFF se nao houver. */
        int tint(Block b, int y);
    }

    private static final int[] OFFS = {0, 2};

    public static boolean isVegetation(Block b) { return b instanceof BlockLeaves || b instanceof BlockLog; }

    /** out[0]=altura da superficie (y+1, inclui copas), out[1]=ARGB, out[2]=flag agua, out[3]=altura do CHAO sob a vegetacao (y+1). */
    public static boolean sampleColumn(BlockReader rd, int h, boolean vegetation, int[] out) {
        if (h <= 0) return false;
        int y = Math.min(h, 255);
        int sy;
        IBlockState st;
        IBlockState above = rd.get(y);
        Block ab = above.getBlock();
        if (ab == Blocks.snow_layer || ab == Blocks.carpet) {
            sy = y;
            st = above;
        } else {
            sy = y - 1;
            st = null;
            while (sy > 0) {
                IBlockState s = rd.get(sy);
                Block b = s.getBlock();
                if (b.getMaterial() != Material.air && b.getMapColor(s) != MapColor.airColor && (vegetation || !isVegetation(b))) {
                    st = s;
                    break;
                }
                sy--;
            }
            if (st == null) return false;
        }
        Block b = st.getBlock();
        boolean water = b.getMaterial() == Material.water;
        int tint = rd.tint(b, sy);
        int rgb;
        if (tint != -1 && (tint & 0xFFFFFF) != 0xFFFFFF) {
            rgb = tint & 0xFFFFFF;
        } else {
            rgb = b.getMapColor(st).colorValue & 0xFFFFFF;
            if (rgb == 0) rgb = 0x808080;
        }
        if (water) {
            int d = 0;
            while (d < 16 && sy - d - 1 > 0 && rd.get(sy - d - 1).getBlock().getMaterial() == Material.water) d++;
            float f = 1.0f - 0.55f * (d / 16.0f);
            int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), bl = (int) ((rgb & 255) * f);
            rgb = (r << 16) | (g << 8) | bl;
        }
        // Chao real: se o topo e copa/tronco, desce ate o primeiro bloco que nao e ar nem vegetacao.
        // O relevo do LOD usa o chao (copas viravam "montanhas verdes" gigantes); a cor continua sendo a da copa.
        int gy = sy;
        if (isVegetation(b)) {
            int g = sy - 1;
            while (g > 0) {
                Block gb = rd.get(g).getBlock();
                if (gb.getMaterial() != Material.air && !isVegetation(gb)) break;
                g--;
            }
            if (g > 0) gy = g;
        }
        out[0] = sy + 1;
        out[1] = 0xFF000000 | rgb;
        out[2] = water ? 1 : 0;
        out[3] = gy + 1;
        return true;
    }

    /**
     * Amostra um chunk inteiro (16 celulas 4x4). Retorna false se nao houver dados validos.
     * h[] = topo MAXIMO das 4 colunas amostradas (conservador, usado pela oclusao de jogadores);
     * fl[] = bit0 agua | bits2..7 = "queda" (topo maximo - altura MEDIA do chao), usada pela malha do terreno.
     */
    public static boolean sampleChunk(int[] heightMap /* 256, z<<4|x */, BlockReaderFactory f, boolean vegetation,
                                      short[] h, int[] c, byte[] fl) {
        int[] col = new int[4];
        boolean any = false;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                int bestTop = Integer.MIN_VALUE, bestCol = 0, bestFlag = 0;
                int groundSum = 0, groundN = 0;
                for (int oz : OFFS) {
                    for (int ox : OFFS) {
                        int lx = i * 4 + ox, lz = j * 4 + oz;
                        BlockReader rd = f.reader(lx, lz);
                        if (!sampleColumn(rd, heightMap[lz << 4 | lx], vegetation, col)) continue;
                        groundSum += col[3];
                        groundN++;
                        if (col[0] > bestTop) { bestTop = col[0]; bestCol = col[1]; bestFlag = col[2]; }
                    }
                }
                int s = j * 4 + i;
                if (bestTop == Integer.MIN_VALUE) {
                    h[s] = Region.UNKNOWN; c[s] = 0; fl[s] = 0;
                } else {
                    int meanGround = Math.round(groundSum / (float) groundN);
                    int drop = Math.max(0, Math.min(Region.DROP_MAX, bestTop - meanGround));
                    h[s] = (short) bestTop; c[s] = bestCol;
                    fl[s] = (byte) ((bestFlag & 1) | (drop << Region.DROP_SHIFT));
                    any = true;
                }
            }
        }
        return any;
    }

    public interface BlockReaderFactory { BlockReader reader(int lx, int lz); }

    /** Amostra um chunk vivo do cliente e grava na regiao. Retorna true se houve dado. */
    public static boolean scanChunk(final World w, final int cx, final int cz, com.ultraview.lod.core.LODManager mgr) {
        final Chunk ch = w.getChunkFromChunkCoords(cx, cz);
        if (ch == null || ch.isEmpty()) return false;
        int[] hm = new int[256];
        boolean nonZero = false;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int v = ch.getHeightValue(x, z);
                hm[z << 4 | x] = v;
                if (v > 0) nonZero = true;
            }
        if (!nonZero) return false;
        final int wx0 = cx << 4, wz0 = cz << 4;
        short[] h = new short[16];
        int[] c = new int[16];
        byte[] f = new byte[16];
        boolean ok = sampleChunk(hm, new BlockReaderFactory() {
            public BlockReader reader(final int lx, final int lz) {
                return new BlockReader() {
                    public IBlockState get(int y) { return ch.getBlockState(new BlockPos(wx0 + lx, y, wz0 + lz)); }
                    public int tint(Block b, int y) { return b.colorMultiplier(w, new BlockPos(wx0 + lx, y, wz0 + lz), 0); }
                };
            }
        }, LODConfig.vegetationDetail > 0, h, c, f);
        if (!ok) return false;
        Region r = mgr.getOrCreateRegion(cx >> 4, cz >> 4);
        if (r.putChunk(cx & 15, cz & 15, h, c, f)) mgr.profiler.chunkChanges.incrementAndGet();
        return true;
    }

    /** True se o chunk do cliente tem dados reais (usado para a mascara de transicao). */
    public static boolean isLive(Chunk ch) {
        return ch != null && !ch.isEmpty() && ch.getHeightValue(8, 8) > 0;
    }
}
