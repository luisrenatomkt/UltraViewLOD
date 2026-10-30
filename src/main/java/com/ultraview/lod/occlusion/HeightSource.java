package com.ultraview.lod.occlusion;

/** Fonte de alturas do LOD para os testes de oclusao (implementada pelo LODManager; falsa nos testes). */
public interface HeightSource {
    int UNKNOWN = Integer.MIN_VALUE;
    /** Altura da superficie conhecida do LOD no bloco (x,z) ou UNKNOWN. */
    int surfaceHeight(int blockX, int blockZ);
    /** True se o cliente tem o chunk real carregado (nesse caso o raycast do mundo ja cobre o trecho). */
    boolean isRealChunkLoaded(int blockX, int blockZ);
}
