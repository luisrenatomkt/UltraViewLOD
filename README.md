# UltraView LOD (Minecraft 1.8.9 Forge) — mod ID `ultraviewlod`

Mod **somente cliente** que desenha terreno distante (até 8000 blocos configuráveis) com malha 3D simplificada + textura de cores por região,
em cache (memória e disco), sem aumentar a distância convencional de chunks.

## Como funciona
1. **Perto**: chunks normais do Minecraft, intactos (blocos, luz, água, mobs, outros mods).
2. **Dados**: o mod resume cada chunk que o cliente recebe em amostras 4x4 (altura do topo, cor com tint de bioma, água). No singleplayer ele também lê (somente leitura, em segundo plano) os arquivos `.mca` já gerados.
3. **Níveis**: regiões de 256x256 blocos viram heightfield com células de 4, 8, 16 ou 32 blocos conforme a distância (histerese contra piscar) + uma textura 64/32/16 px por região. Altura por célula = máximo (preserva silhuetas e é conservador para oclusão).
4. **Transição**: chunks que o Minecraft já desenha são "mascarados" (sem geometria duplicada); a neblina do Minecraft é substituída pela do LOD para não haver faixa de neblina na emenda.
5. **Cache**: LRU em memória (limite configurável; sob pressão o mod simplifica mais), disco por mundo/dimensão com CRC32, versão e validade (padrão 72h).
6. **Jogadores**: continuam entidades reais do Minecraft (modelo, skin, animação). Além de 64 blocos só são desenhados com linha de visão (raycast no mundo + geometria do LOD). Nomes só aparecem com linha de visão. Sem configuração para desligar isso. Área desconhecida = oculto.

## Instalar
Forge 1.8.9 (11.15.1.2318) + Java 8. Coloque `UltraViewLOD-1.8.9-1.0.0.jar` em `.minecraft/mods`.

## Compilar
Veja o tutorial do GitHub (workflow já incluído em `.github/workflows/build.yml`). Local: Java 8 + Gradle 2.14.1 → `gradle setupDecompWorkspace build` → `build/libs/`.

## Configurar / usar
- Menu **Mods → UltraView LOD → Config** ou `config/ultraviewlod.cfg`.
- Comandos: `/uvlod status | toggle | debug | distance <blocos> | clear | reset`.
- `/uvlod debug` mostra métricas reais no texto do F3.
- Cache: `.minecraft/ultraviewlod/cache/<mundo>/dim<N>/`. `/uvlod clear` apaga só o do mundo atual.

## Limitações conhecidas (leia)
- **O LOD só mostra o que o cliente já viu ou leu do save.** Em servidor, regiões nunca recebidas não aparecem (não inventamos). Mudanças fora do alcance de chunks do cliente não são detectáveis (cache pode ficar defasado até a validade expirar).
- Alterações de blocos são detectadas por **reamostragem** dos chunks carregados (poucos segundos), mais re-leitura imediata ao quebrar/colocar.
- Jogadores só existem no cliente dentro do que o **servidor envia**; o mod não amplia isso. O alcance de render de entidades é o do próprio Minecraft (ajustado por reflexão; se a reflexão falhar, o log avisa).
- Água é opaca e sem ondas; sem luz de blocos no LOD (só brilho do dia); sem blending alfa entre LOD e chunks (transição por máscara + histerese + neblina).
- Precisão de profundidade a milhares de blocos é limitada (plano próximo 0,05 do Minecraft).
- Dimensões sem céu (Nether/End) ficam sem LOD.
- Mods de renderização (OptiFine etc.) podem conflitar com o ajuste de projeção/neblina; não testado.
- Disco: o limite vale por mundo.

## Roteiro de testes no jogo
1 carregar; 2 perto normal; 3 horizonte além dos chunks; 4 construção distante; 5 quebrar blocos e ver atualizar; 6 sair/entrar e ver `cache disco reutilizado` subir; 7 comparar FPS ligado/desligado (`/uvlod toggle`); 8 `memória estimada` ≤ limite; 9 servidor autorizado: jogadores animados; 10 parede/montanha entre você e outro jogador: corpo (>64 blocos) e nick somem; 11 mover rápido; 12 áreas desconhecidas ficam vazias.
