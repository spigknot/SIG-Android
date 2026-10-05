# Verificação caso a caso — correções FFmpeg (commit 85b889f)

Data: 2026-08-31 · Escopo: os 54 findings listados (Girar 13 · Cortar 8 · Juntar 14 · Extrair 8 · Inserir 8 · Limpar 3)

Método: leitura do código ATUAL (HEAD = 85b889f, working tree limpo para os arquivos FFmpeg) item por item, conferindo cada evidência citada. Teste `FfmpegMediaPoliciesTest` executado: **8 testes, 0 falhas** (BUILD SUCCESSFUL, 2026-08-31 11:12).

## Resultado

| Ferramenta | Corrigido | Parcial | Não corrigido |
|---|---|---|---|
| Girar vídeo | 11 | 2 | 0 |
| Cortar áudio/vídeo | 6 | 1 | 1 |
| Juntar vídeo/áudio | 10 | 3 | 1 |
| Extrair áudio | 7 | 1 | 0 |
| Inserir áudio | 7 | 1 | 0 |
| Limpar áudio | 2 | 1 | 0 |
| **Total (54)** | **43** | **9** | **2** |

(1 dos 9 parciais — Extrair 4 — é cosmético: o índice da faixa é validado antes, falta só o "?" de defesa em profundidade.)

Corrigidos de forma comprovada, com destaque para os mais críticos: sentido da rotação por metadados (subtração + sinal da tag legada invertido, com teste unitário), `display_rotation 0` sempre emitido, `-map_metadata 0`, `-noautorotate` em todas as entradas, segmentos MKV com fallback sequencial, letterbox em vez de crop no Juntar, Smart Join desativado com aviso, perfil agregado (máximo) em vez do primeiro clipe, canais multicanal reconhecidos, presets restauráveis no Extrair, rota única de reencode amostral no Inserir, Limpar preservando taxa/canais/profundidade da origem.

---

# RELATÓRIO DE PENDÊNCIAS

Ferramenta de girar vídeo
Arquivo: [FfmpegRotateVideoActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegRotateVideoActivity.kt)
6. Finding (PARCIAL): o modo somente metadados deixou de gerar sempre MP4 — a saída agora preserva a extensão da origem (`buildOutputName`, linha 1821-1825, com `safeContainerExtension` caindo em mkv). Mas continua sem validar os codecs contra o container escolhido: um container exótico remapeado para .mkv com `-map 0 -c copy` (FfmpegMediaPolicies.kt, 13–21) ainda pode falhar no FFmpeg sem nenhum aviso na UI.
   Fix: validar codecs/streams contra o container antes de executar, ou avisar quando a origem tiver container não reconhecido.
   Linhas: 1821–1825; FfmpegMediaPolicies.kt, 13–21 e 106–109.
   Gravidade: ALTA.
13. Finding (PARCIAL): o reencode agora preserva todos os áudios, legendas, dados e anexos (`-map 0:a? 0:s? 0:d? 0:t?` + `-c copy` nos dois caminhos), mas o vídeo continua restrito à primeira faixa: `-map 0:v:0` no sequencial (linha 1251) e na rotação de cada trecho (linha 1048). Um arquivo com duas trilhas de vídeo perde a segunda em silêncio.
   Fix: mapear todas as faixas de vídeo preserváveis ou informar/bloquear explicitamente quando houver vídeo múltiplo.
   Linhas: 1048, 1251.
   Gravidade: ALTA.

Ferramenta de cortar áudio/vídeo
Arquivo: [FfmpegCutActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegCutActivity.kt)
5. Finding (PARCIAL): HEVC deixou de derrubar o caminho rápido (híbrido aceita h264/hevc quando o encoder selecionado é da mesma família, linha 677) e existe diálogo quando o encoder escolhido conflita com a origem (linhas 508–527). Mas continua sem nenhum aviso prévio quando o corte cai em reencode completo por outros motivos: codec fora de h264/hevc (linha 678) ou keyframes não encontrados (linhas 686–689) caem no `executeFullPrecisionFallback` em silêncio.
   Fix: mostrar antes da execução que o caminho rápido não será usado (diálogo ou etapa no tracker com o motivo).
   Linhas: 677–689.
   Gravidade: MÉDIA.
7. Finding (NÃO CORRIGIDO): cortar áudio continua sempre reencodando. `preciseAudioEncoderArguments` está inalterado no essencial: WAV → `pcm_s16le` (derruba 24/32-bit), MP3 → CBR com bitrate fixo, sem qualquer `-c:a copy` em todo o arquivo (a busca por `-c:a copy` no Cortar não retorna nada). O branch explícito m4a/aac foi removido (cai no else → aac, equivalente).
   Fix: oferecer stream copy quando codec e container forem compatíveis (espelhando `canCopyAudioWithoutConversion` do Extrair) e expor a qualidade de saída.
   Linhas: 961–969.
   Gravidade: MÉDIA.

Ferramenta de juntar vídeo/áudio
Arquivo: [FfmpegJoinVideosActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegJoinVideosActivity.kt)
3. Finding (PARCIAL): foi adicionado seletor de faixa de áudio por clipe (`requestAudioTrack`, linhas 2638–2649; especificador `N:a:M` via FfmpegMediaPolicies), mas a aridade do concat continua 1: `concat=n=N:v=0:a=1` (linhas 1109 e 1120) e `v=1:a=1` (1605, 1640) — um único áudio por clipe, e `SmartJoinPlanner.profilesCompatible` (128–161) não compara a quantidade de faixas entre clipes.
   Fix: concatenar todas as faixas selecionadas ou avisar que as adicionais serão removidas.
   Linhas: 1109, 1120, 1605, 1640; SmartJoinPlanner.kt, 128–161.
   Gravidade: ALTA.
4. Finding (PARCIAL): o reencode de áudio agora escolhe encoder por extensão/codec (`audioEncoderForOutput`, 1152–1170) com taxa/canais do perfil agregado e sem `-b:a` para lossless (1138–1146, 1543–1554). Mas continua não existindo `-c:a copy` em nenhum caminho: áudios idênticos entre clipes ainda são reencodados sempre que o reencode roda. Obs.: os trechos com `-c:a aac` fixo (1246, 1380) pertencem ao Smart Join, hoje inalcançável.
   Fix: preservar o codec quando todos os clipes forem compatíveis ou informar a conversão.
   Linhas: 1130–1146, 1543–1554.
   Gravidade: ALTA.
6. Finding (PARCIAL): a validação nova olha topologia, não parâmetros: `validateSupportedStreamTopology` (2599–2628) exige 1 vídeo / ≥1 áudio e o caminho direto bloqueia perfis diferentes, orientações diferentes e multitrilha escolhida (linhas 720–727). Porém `buildDirectConcatArguments` (1190–1204) continua `-map 0 -c copy` puro, sem validar codec de áudio, perfil AAC, sample format ou extradata entre clipes — compatibilidade de parâmetros segue sem checagem.
   Fix: validar codec, perfil, taxa, canais, extradata e container antes do concat direto.
   Linhas: 1190–1204; 2599–2628.
   Gravidade: ALTA.
8. Finding (NÃO CORRIGIDO na parte essencial): a padronização para WAV deixou de ser 16 kHz mono — usa taxa/canais do perfil agregado (1130–1136) —, mas a decisão continua SILENCIOSA: nenhum diálogo, aviso ou linha de status informa que a saída virou WAV (só o nome do arquivo muda, linha 569 + `buildJoinedOutputName`, 2563+). E o rótulo exibido "Normalizando pelo primeiro áudio" (linha 1047) está errado: a normalização é pelo perfil agregado (máximo), não pelo primeiro.
   Fix: pedir confirmação para a conversão (ou ao menos linha de status explícita) e corrigir o rótulo.
   Linhas: 569, 1047, 1130–1136.
   Gravidade: ALTA.

Ferramenta de extrair áudio
Arquivo: [FfmpegExtractAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegExtractAudioActivity.kt)
2. Finding (PARCIAL): o caminho de cópia existe agora (`canCopyAudioWithoutConversion`, 905–940, com `-c:a copy` em 873–876 e rótulo "cópia sem perdas"). Mas o reencode continua restrito: WAV sempre `pcm_s16le` (linha 880, derruba 24/32-bit quando a cópia não se aplica) e MP3 sempre CBR com `-minrate`/`-maxrate` iguais ao alvo (linha 883), sem escolha CBR×VBR para o usuário.
   Fix: escolher pcm_s24le/pcm_s32le/pcm_f32le conforme a profundidade da origem e expor CBR×VBR.
   Linhas: 880, 883.
   Gravidade: MÉDIA.
4. Finding (PARCIAL-cosmético): a faixa deixou de ser sempre a 0 — contagem prévia rejeita arquivo sem áudio e pergunta quando há mais de uma (744–756) —, mas o mapa literal continua sem "?": `-map 0:a:$audioTrack` (linha 872). Na prática o índice é sempre válido; é só defesa em profundidade.
   Fix: usar `-map 0:a:$audioTrack?`.
   Linhas: 872.
   Gravidade: BAIXA (residual).

Ferramenta de inserir áudio
Arquivo: [FfmpegInsertAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegInsertAudioActivity.kt)
6. Finding (PARCIAL-cosmético): o índice da faixa passou a ser escolhido e validado (rejeita sem áudio, seletor com múltiplas faixas, linhas 491–510; filtro `[0:a:track]`/`[1:a:track]` em 613/620/624), mas sem "?". As ocorrências `-map 0:a:0` (714–754) pertencem a `executeCopyInsert`/`executeSmartInsert`, hoje sem chamador (caminho morto).
   Fix: adicionar "?" nos mapas do caminho ativo; remover o código morto.
   Linhas: 613, 620, 624.
   Gravidade: BAIXA (residual).

Ferramenta de limpar áudio
Arquivo: [FfmpegCleanAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegCleanAudioActivity.kt)
3. Finding (PARCIAL): a mitigação funciona — `inspectAudioSource` exige exatamente 1 faixa de áudio antes de executar (linhas 175–179), então o índice 0 é sempre válido —, mas o comando literal continua `-map 0:a:0` sem "?" e sem validação no próprio FFmpeg (linha 271).
   Fix: `-map 0:a:0?` como defesa em profundidade.
   Linhas: 271.
   Gravidade: BAIXA (residual).

Pendências transversais
1. (PARCIAL) Legendas: Girar e Cortar preservam; Juntar agora REJEITA qualquer vídeo com legenda em toda junta (`validateSupportedStreamTopology`, linha 2618, chamada em 561–563 sem distinguir o modo) — MKV com legenda que antes era unido (com descarte) agora aborta. Regressão de usabilidade introduzida pelo commit.
   Fix: restringir a checagem ao modo com transições ou oferecer "Remover legendas e continuar" no diálogo.
   Linhas: 561–563, 2618.
   Gravidade: MÉDIA.
2. (PARCIAL) Testes: `FfmpegMediaPoliciesTest` roda e passa (8/8), cobrindo a política compartilhada; nenhum teste cobre os builders por ferramenta (Girar/Cortar/Juntar/Extrair/Inserir/Limpar).
   Fix: testes de montagem de comando por ferramenta.
   Gravidade: BAIXA.

## Observações (não são findings, vieram do commit)
- Código morto que reativa os bugs se chamado: `executeCopyInsert`/`executeSmartInsert` + `canCopyDirectly`/`smartInsertViable` (Inserir, 531–534, 707, 732), `executeSmartJoinExperiment` (Juntar, 1206, inalcançável via `smartJoinChecked = false` na 539), `describeAudioFile` (Extrair, 1653, sem chamador), parâmetro `letterbox` nunca false (Juntar, 1645).
- Custo de I/O: o corpo do corte híbrido usa `-ss` depois de `-i` (linha 863) — corrige a duplicação de GOP, mas demuxa o arquivo do início (lento em vídeos longos ao cortar o fim).
