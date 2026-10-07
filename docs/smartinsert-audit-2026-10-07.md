# Smart Insert Android — auditoria de 07/10/2026

Escopo: ferramenta **Inserir áudio**, em `FfmpegInsertAudioActivity.kt`, e os antigos geradores de comandos de `FfmpegMediaPolicies.kt`. Foram preservadas as alterações anteriores de SmartJoin e os demais trabalhos existentes no checkout.

## Falhas reproduzidas

O caminho antigo usava `-ss`/`-t` em stream copy, concatenando cortes arbitrários com uma emenda codificada separadamente. Os limites recaíam dentro dos pacotes comprimidos: partes do principal eram repetidas e o atraso/padding dos encoders se acumulava. No FLAC, o cabeçalho conservava a duração do primeiro trecho.

Reprodução inicial com os antigos geradores Kotlin e FFmpeg 8.0.1 no Windows: principal de 8,137 s, inserido de 1,731 s, ponto de 3,123 s e nenhuma transição. Soma desejada: **9,868 s = 473.664 amostras a 48 kHz**.

| Codec | Amostras decodificadas no caminho antigo | Diferença para a soma | Duração declarada |
|---|---:|---:|---:|
| PCM | 475.312 | +34,333 ms | 9,902333 s |
| ALAC | 477.760 | +85,333 ms | 9,902333 s |
| FLAC | 478.272 | +96 ms | **8,137 s** |
| AAC | 477.184 | +73,333 ms | 9,881 s |
| MP3 | 476.976 | +69 ms | 9,937 s |
| Opus | 480.816 | +149 ms | 10,034 s |
| Vorbis | 479.792 | +127,667 ms | 9,874333 s |

Esses números são da reprodução local anterior à correção; não são uma medição do caminho antigo no Ace. Em codecs com perdas, a diferença bruta também inclui o padding normal do formato. A prova final compara cada saída com uma recodificação contínua feita pelo mesmo encoder.

Outras falhas encontradas:

- **Sem transição:** o tempo positivo chegava ao gerador de fades mesmo quando a curva era nula. Transição zero e ausência de efeito não eram tratados de maneira uniforme.
- **Faixas:** os trechos copiados e a emenda selecionavam sempre `a:0`, ignorando a faixa escolhida pelo usuário.
- **Fallback:** substituía o efeito Smart, que suaviza apenas o inserido, pelo crossfade convencional. Isso alterava o áudio do principal e diminuía a duração.
- **Perfis:** o MIME do MediaExtractor era usado como nome de codec; falhas eram substituídas por AAC, 48 kHz e estéreo. ALAC, FLAC e profundidades PCM podiam ser identificados incorretamente.
- **Prévia:** tocava os arquivos originais em sequência, sem a transição e sem respeitar a faixa selecionada.
- **Tempos:** havia arredondamento para milissegundos nos filtros, limitação silenciosa a cinco segundos e aceitação de valores não finitos.
- **Cancelamento/publicação:** faltavam cancelamento persistente entre etapas, validação da montagem final e limpeza consistente da saída incompleta.
- **Metadados:** os comentários armazenados na própria faixa Opus/Vorbis não acompanhavam a saída filtrada.
- **Compatibilidade das curvas:** `quat`, `quatr`, `qsin2` e `hsin2` aparecem no FFmpeg 8, mas não no FFmpeg 6 do pacote Android. A prova no Ace encerrou o processo de teste ao chegar a `quat`. A correção compõe o mesmo ganho com filtros aceitos pelo pacote existente, sem atualizar os binários. A diferença de catálogo pode ser conferida no [fonte oficial do FFmpeg 6](https://github.com/FFmpeg/FFmpeg/blob/n6.0/libavfilter/af_afade.c) e no [fonte oficial do FFmpeg 8](https://github.com/FFmpeg/FFmpeg/blob/n8.0/libavfilter/af_afade.c).

## Estratégia adotada

Preservar a cópia onde a montagem é confiável e usar recodificação contínua, com aviso explícito, apenas nos codecs/contêineres sem emendas confiáveis. Esse comportamento foi autorizado pelo usuário; a recodificação preserva a opção Smart e o efeito escolhido.

| Formato | Estratégia |
|---|---|
| PCM em WAV/RF64 | Copiar os bytes do principal em limites de amostras. Tratar somente o inserido; se não houver efeito e seu PCM for compatível, copiar ambos. |
| ALAC em M4A | Copiar os pacotes completos antes/depois do corte. Codificar somente o inserido e a fração de pacote atravessada pelo corte. |
| FLAC nativo | Copiar os subframes comprimidos, recalcular numeração e CRC dos headers e atualizar STREAMINFO. Codificar apenas a emenda e o inserido. |
| AAC, MP3, Opus, Vorbis, WMA e outros casos não compatíveis | Uma codificação contínua, mantendo a suavização apenas do inserido quando Smart estiver marcado. |

O primeiro benchmark de uma hora no Ace mostrou PCM em 0,617 s, contra 2,882 s no comparador integral. ALAC e FLAC conservaram o áudio, mas a análise de pacotes JSON custou o ganho de velocidade: 8,034 versus 7,680 s e 4,880 versus 4,401 s, respectivamente. Isso motivou a substituição da tabela JSON completa por uma tabela CSV enxuta, mantendo a mesma verificação dos limites. A medição da versão final e os gates estão no [relatório de correção](smartinsert-fix-2026-10-07.md).

## Evidência e limites

Os arquivos sintéticos e os logs ficam em `build/smartinsert_audit/`, ignorado pelo Git. Os testes permanentes verificam o pipeline de produção, não uma reimplementação do algoritmo. A prova no **Ace 2 Pro, serial 1164a04**, carrega as classes do APK de teste e usa o **FFmpegKit n6.0 do pacote 11-arm64-v8a**, por `app_process`, sem instalar ou publicar APK. O OnePlus 15 não foi usado.

A prova do pipeline no aparelho não substitui um teste manual completo da Activity, dos seletores SAF ou da reprodução pelo MediaPlayer. Essas limitações são descritas no relatório final.
