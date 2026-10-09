# Auditoria dos players FFmpeg Android — 2026-10-09

Escopo: Cortar, Girar, Extrair Áudio, reprodução dos clipes e do resultado de
Juntar, e prévia de Inserir Áudio. Limpar Áudio não implementa player próprio.
Não houve alteração nos comandos de exportação, nos binários nativos ou nas
versões e URLs de distribuição.

## Gargalos encontrados e correções

| Caminho | Evidência no código anterior | Correção |
|---|---|---|
| Abertura/troca/saída dos players | `setDataSource` e `release` executados na UI; `prepareAsync` não tornava a abertura da fonte assíncrona. | `FfmpegPreviewSource` serializa abertura e liberação em worker e ignora erros de fontes descartadas. |
| Timeline | Girar/Juntar/Inserir enviavam buscas por evento; Cortar/Extrair ainda enviavam buscas periódicas durante arraste. | Debounce de 120 ms, último destino e uma busca em andamento; buscas pendentes e callbacks antigos são descartados na troca/saída. |
| Play depois de buscar | Cortar/Extrair esperavam `playWhenSeekCompletes`, mas não registravam `OnSeekCompleteListener`; o início usava `SEEK_NEXT_SYNC`, podendo saltar adiante. | Callback real de conclusão; busca precisa para o destino pedido; retomada direta quando a posição já está correta. |
| Giro/pinça | Girar atribuía `layoutParams` mesmo sem alterar altura; transformações eram aplicadas a cada evento de toque. | Altura alterada somente quando necessário; viewport aplicado no próximo frame, agrupando eventos. |
| Seleção de área/trim | Comandos e TextViews eram reconstruídos por movimento; Cortar sondava PCM novamente na prévia do comando. | Atualização dos comandos agrupada em Cortar/Girar, PCM sondado uma vez na seleção e texto idêntico não reaplicado. |
| Seleção de mídia | Cortar/Extrair sondavam mídia na UI; Juntar decodificava miniaturas na UI e na resolução original. | Leitura em worker e rejeição de resultados obsoletos; miniaturas até 320×180 (API 27+ decodifica diretamente nesse tamanho). |
| Análises de keyframes | Análises antigas continuavam concorrendo com a mídia atual após nova seleção/saída. | Interrupção e verificação de geração nas análises de prévia. |
| Inserir Áudio | Remoção recursiva da prévia ocorria na UI; mudar velocidade reiniciava uma busca. | Limpeza em worker, velocidade preservando posição e pausa, busca reutilizando a prévia já pronta. |
| Enquadramento | Cortar/resultado de Juntar calculavam a matriz a partir da resolução do vídeo, embora TextureView já redimensione a textura para a view. | Escala relativa às dimensões da view. |

`SEEK_CLOSEST` tem maior custo em pontos fora de keyframes, conforme a
[documentação de MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer#seekTo(long,int)).
Foi preservada a precisão do quadro final: o controle reduz chamadas durante
o gesto e aguarda a conclusão nativa, em vez de substituir o ponto pedido por
um keyframe distante. Em Android 24–25 continua valendo a precisão disponível
na API antiga de `seekTo(int)`.

## Validação

Resultados desta execução: suíte completa com 640 testes, sem falhas ou skips;
26 testes focais de busca/geometria repetidos após a última revisão, sem
falhas ou skips; oito cenários de instrumentação aprovados novamente no APK
final instalado no aparelho autorizado (Android API 36). A suíte contratual do
harness, o inventário de módulos e `git diff --check` também passaram.
Build debug e lint da revisão final aprovados: zero erros de lint, 1.127
avisos no projeto. APK instalado e testado: SHA-256
`8629D0EBEE4BA05A9F9F9AA7F28087C766602CAA10BE3A4AC0A0FAB84773CAEA`.

Suíte pura `FfmpegPreviewSeekQueueTest`: 1.000 eventos agrupados, destino final,
serialização, pedido imediato, cancelamento e descarte. Executar com:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
powershell -File scripts/check-module-map.ps1 -Quiet
git diff --check
```

Instrumentação `FfmpegPreviewPerformanceTest`: oito cenários no dispositivo
autorizado `100.108.27.64:5555` (modelo CPH2747). Usa somente vídeo sintético
H.264/AAC, 1920×1080, 30 fps, 12 segundos e GOP de 240 frames, mais WAV
sintético de 12 segundos. A fixture longa entre keyframes força busca fora de
keyframe. Nenhuma mídia pessoal foi usada.

- Cortar, Extrair e Girar: 301 pedidos, chegada a 4,7 segundos e retomada;
  alteração de velocidade preserva a pausa.
- Girar: callback de viewport não solicita layout sem mudar altura; giro de
  90 graus e pinça ampliam enquanto a posição do vídeo continua avançando.
- Juntar: 300 pedidos alternando dois clipes, destino final e retomada;
  miniaturas limitadas a 320×180.
- Resultado de Juntar: busca e retomada com o player do arquivo resultante.
- Inserir: busca e retomada sem recriar o player nem renderizar nova prévia.
- Fonte bloqueada artificialmente: descarte na UI não espera a leitura;
  callback de erro da fonte cancelada não é entregue.

Os testes de lotes exigem menos de 1 segundo de trabalho na UI para os 300/301
pedidos; o teste de descarte exige menos de 100 ms. Isso mede responsividade
e comportamento, não FPS do decoder. Evidências locais ficam em
`build/preview-audit/` e nos relatórios de teste/lint de `app/build/`.

Para reproduzir no dispositivo escolhido, copiar as fixtures sintéticas para
`cache/ffmpeg-preview-audit.mp4` e `cache/ffmpeg-preview-audit.wav` do APK debug,
instalar o APK de instrumentação e executar:

```powershell
adb -s 100.108.27.64:5555 shell am instrument -w -e class br.gov.sp.pcsp.launcher.FfmpegPreviewPerformanceTest br.gov.sp.pcsp.launcher.test/androidx.test.runner.AndroidJUnitRunner
```

## Limites

Os testes não garantem a mesma fluidez em todos os aparelhos, vídeos 4K/HEVC,
arquivos corrompidos ou provedores de conteúdo remotos. Uma leitura nativa
bloqueada pode demorar a liberar seu worker; a UI não espera essa liberação.
Juntar ainda prepara o próximo clipe na passagem entre arquivos, portanto a
prévia não oferece transição sem intervalo entre decoders. Inserir ainda
precisa renderizar a prévia inicial ou após mudar efeitos/conteúdo; essa
operação permanece em segundo plano e continua cancelável.
