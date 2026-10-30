package com.ultraview.lod.config;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.Map;

public final class LODConfig {
    public static final String GENERAL = "general", DISTANCE = "distance", QUALITY = "quality",
        PERFORMANCE = "performance", ENTITIES = "entities", DEBUG = "debug";
    public static final String[] CATEGORIES = {GENERAL, DISTANCE, QUALITY, PERFORMANCE, ENTITIES, DEBUG};
    public static Configuration config;

    public static volatile boolean enabled = true, distantRendering = true, cacheEnabled = true,
        prefetch = true, prefetchFromSave = true, memorySaver = false, hideFarNonPlayers = true, debugOverlay = false;
    public static volatile int visualDistance = 1000, conventionalRenderDistance = 0, transitionHysteresis = 8,
        entityRenderDistance = 64, lodQuality = 2, textureResolution = 64, vegetationDetail = 1,
        scanChunksPerTick = 4, cacheMemoryMB = 256, diskLimitMB = 512, cacheMaxAgeHours = 72,
        workers = 2, maxPendingTasks = 64, regionsPerCycle = 4, uploadBudgetMs = 2, loadPriority = 1;

    private LODConfig() { }

    public static void init(File file) {
        config = new Configuration(file);
        sync();
    }

    public static void sync() {
        Configuration c = config;
        enabled = c.getBoolean("enabled", GENERAL, true, "Ativa o UltraView LOD.");
        distantRendering = c.getBoolean("distantRendering", GENERAL, true, "Ativa a renderizacao distante.");
        cacheEnabled = c.getBoolean("cacheEnabled", GENERAL, true, "Usa o cache em disco.");
        prefetch = c.getBoolean("prefetch", GENERAL, true, "Carregamento antecipado: prepara tambem regioes fora do campo de visao.");
        prefetchFromSave = c.getBoolean("prefetchFromSave", GENERAL, true, "Singleplayer: le (somente leitura) os arquivos .mca ja gerados do mundo para preparar o LOD.");

        visualDistance = c.getInt("visualDistance", DISTANCE, 1000, 128, 8000, "Distancia visual do terreno em blocos (sugestoes: 256, 512, 1000, 2000, 5000; qualquer valor serve).");
        conventionalRenderDistance = c.getInt("conventionalRenderDistance", DISTANCE, 0, 0, 32, "Distancia de chunks do Minecraft. 0 = nao alterar (padrao). So muda se VOCE escolher.");
        transitionHysteresis = c.getInt("transitionHysteresis", DISTANCE, 8, 0, 30, "Zona de transicao entre niveis de detalhe, em % (histerese anti-piscada).");
        entityRenderDistance = c.getInt("entityRenderDistance", DISTANCE, 64, 64, 512, "Distancia de renderizacao de jogadores em blocos (limitada pelo que o servidor envia).");

        lodQuality = c.getInt("lodQuality", QUALITY, 2, 1, 4, "Qualidade do LOD (1 baixa ... 4 ultra): afasta os limiares de simplificacao.");
        textureResolution = c.getInt("textureResolution", QUALITY, 64, 16, 64, "Resolucao maxima da textura por regiao (16, 32 ou 64).");
        vegetationDetail = c.getInt("vegetationDetail", QUALITY, 1, 0, 1, "Vegetacao: 1 = copas de arvores aparecem; 0 = ignora folhas/troncos (mostra o chao). Vale para novas leituras.");
        scanChunksPerTick = c.getInt("scanChunksPerTick", QUALITY, 4, 1, 16, "Frequencia de atualizacao: chunks reamostrados por tick.");

        cacheMemoryMB = c.getInt("cacheMemoryMB", PERFORMANCE, 256, 32, 2048, "Limite de memoria (estimado) para dados + malhas em cache.");
        diskLimitMB = c.getInt("diskLimitMB", PERFORMANCE, 512, 16, 8192, "Limite de disco POR MUNDO.");
        cacheMaxAgeHours = c.getInt("cacheMaxAgeHours", PERFORMANCE, 72, 1, 8760, "Descarta cache mais antigo que isso (evita imagens desatualizadas).");
        workers = c.getInt("workers", PERFORMANCE, 2, 1, 4, "Trabalhadores em segundo plano (requer reiniciar o jogo).");
        maxPendingTasks = c.getInt("maxPendingTasks", PERFORMANCE, 64, 8, 512, "Limite de tarefas pendentes.");
        regionsPerCycle = c.getInt("regionsPerCycle", PERFORMANCE, 4, 1, 16, "Regioes processadas por ciclo (tick).");
        uploadBudgetMs = c.getInt("uploadBudgetMs", PERFORMANCE, 2, 1, 10, "Tempo maximo por frame criando texturas/listas OpenGL.");
        loadPriority = c.getInt("loadPriority", PERFORMANCE, 1, 0, 2, "Prioridade: 0 = mais proximas primeiro, 1 = campo de visao primeiro, 2 = equilibrado.");
        memorySaver = c.getBoolean("memorySaver", PERFORMANCE, false, "Modo de economia de memoria.");

        hideFarNonPlayers = c.getBoolean("hideFarNonPlayers", ENTITIES, true, "Nao desenha mobs alem do alcance normal (so jogadores usam o alcance estendido).");

        debugOverlay = c.getBoolean("debugOverlay", DEBUG, false, "Mostra metricas no texto de debug.");
        if (c.hasChanged()) c.save();
    }

    public static void restoreDefaults() {
        for (String name : CATEGORIES) {
            ConfigCategory cat = config.getCategory(name);
            for (Map.Entry<String, Property> e : cat.entrySet()) e.getValue().setToDefault();
        }
        sync();
    }
}
