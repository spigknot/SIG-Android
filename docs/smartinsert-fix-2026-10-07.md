# Smart Insert Android — correções e prova funcional

O Smart Insert foi corrigido no código Android, mantendo cópia do principal em **PCM/WAV, ALAC/M4A e FLAC nativo**. A [auditoria](smartinsert-audit-2026-10-07.md) registra os defeitos reproduzidos no caminho anterior.

## Resultado esperado das opções

- **Smart marcado:** principal preservado; a curva suaviza apenas o inserido. A duração é a soma dos áudios, sem sobreposição oculta.
- **Smart desmarcado:** recodificação contínua; as curvas de crossfade sobrepõem os trechos. A duração desconta o tempo efetivamente sobreposto em cada emenda. Fade de entrada/saída mantém a soma.
- **Zero segundo ou Sem transição:** nenhum fade ou crossfade. Em WAV compatível, principal e inserido são copiados sem executar encoder.
- **Ganhos constantes (`nofade`):** no Smart, não há alteração das amostras; WAV compatível também usa cópia de ambos. Na inserção convencional, a sobreposição com ganho constante continua respeitando o tempo escolhido.
- **Faixas:** a escolha vale no principal, inserido, trechos copiados e prévia. Duas seleções do mesmo arquivo mantêm escolhas independentes.
- **Tempos:** ponto, limites e fades calculados por amostras. Valores inválidos são recusados; o limite real de uma transição considera os trechos vizinhos. Não há corte silencioso em cinco segundos.

AAC, MP3, Opus, Vorbis, WMA e contêineres sem emendas confiáveis usam uma codificação contínua com aviso na tela. Esse caminho mantém a opção Smart e seu efeito; não troca silenciosamente para o crossfade convencional. Não há retry geral depois de qualquer erro.

## Implementação

| Fonte | Responsabilidade |
|---|---|
| `SmartInsertPlanner.kt` | Perfil nativo, amostras, curvas, filtros, limites de pacotes e mapeamento da prévia. |
| `SmartInsertPipeline.kt` | Comandos, seleção de faixas, cópia/encode, validação, cancelamento e publicação. |
| `SmartInsertWave.kt` | Montagem PCM por bytes, incluindo extensible/RF64, profundidade e metadados INFO. |
| `SmartInsertFlac.kt` | Rebase dos headers, numeração por amostras, CRC e STREAMINFO, copiando subframes comprimidos. |
| `FfmpegInsertAudioActivity.kt` | Seleção/cópia SAF em background, estado da tela, sessões FFmpegKit e reprodução da prévia filtrada. |

Os antigos geradores de Smart Insert foram removidos de `FfmpegMediaPolicies.kt`. A prévia e a exportação usam o mesmo planejador. A leitura do MP3 desconta atraso e padding; se a busca pelo índice não alcançar o último pacote, lê os timestamps completos sem decodificar. Para ALAC/FLAC, a tabela CSV enxuta evita o custo de milhares de objetos JSON e mensagens extras no FFmpegKit.

As quatro curvas ausentes no FFmpeg 6 são produzidas com filtros compatíveis: quarta potência por quatro ganhos lineares, quarta raiz por expressão por amostra e senos ao quadrado por dois ganhos iguais. No crossfade, as curvas são aplicadas antes da sobreposição com ganho constante, respeitando inclusive a diferença de uma amostra do fade de saída. A comparação independente com as curvas nativas do FFmpeg 8 aceitou erro máximo de uma unidade PCM16.

A publicação ocorre somente depois da validação. Falhas e cancelamento removem os temporários e a saída incompleta; uma saída anterior permanece disponível. O cancelamento usa o ID da própria sessão e é verificado entre etapas.

## Prova de duração e qualidade

No Ace 2 Pro (serial inicial **1164a04**, rodada final em **100.114.88.45:5555**), foram carregadas as classes do APK debug por `app_process` e usados os binários **FFmpegKit n6.0, pacote 11-arm64-v8a**. Passaram **262 exportações nas rodadas executadas**, além das seis saídas do benchmark de uma hora. A última rodada repetiu e ampliou a matriz adicional com o APK final. Não foi necessário instalar APK ou alterar os pacotes nativos. O OnePlus 15 não foi usado.

Principal 8,137 s, inserido 1,731 s e corte 3,123 s: em Smart, o plano final permanece **9,868 s = 473.664 amostras a 48 kHz**, para transições de **0, 0,2, 0,5 e 1 s**. PCM, ALAC, FLAC, MP3, Opus e Vorbis entregaram essa contagem decodificada. Todas as comparações de áudio da matriz principal ficaram idênticas às referências contínuas no mesmo aparelho.

AAC/M4A declarou a duração planejada, mas o decoder FFmpeg entregou 448 amostras de padding adicional; WMA entregou 576 amostras a menos nesse caso. São os mesmos resultados do comparador contínuo, não emendas acumuladas de Smart Insert. Portanto, não se promete contagem PCM universalmente exata em todo formato com perdas.

Os testes permanentes incluem comparação de PCM em 8/16/24/32 bits e float32/64, ALAC/FLAC de 24 bits e estéreo, seleção de faixas, prévia, todas as curvas, cortes no começo/fim e em limites de pacotes, CRC/seek de FLAC, preservação dos corpos comprimidos e ganho linear calculado independentemente. Também verificam cancelamento, falha de encoder e preservação de arquivo existente.

## Velocidade no Ace

Principal sintético mono de **uma hora, 48 kHz**, inserido de 1,731 s a 44,1 kHz, ponto 1.800,123 s e curva linear de 0,5 s. Tempos do próprio pipeline no aparelho, incluindo análise, validação e montagem; envio por ADB e conferência externa não entram nesses tempos.

| Formato | Smart Insert | Comparador contínuo | Ganho medido |
|---|---:|---:|---:|
| PCM/WAV | 0,487 s | 2,469 s | **5,07×** |
| ALAC/M4A | 6,614 s | 7,580 s | **1,15×** |
| FLAC | 2,312 s | 4,371 s | **1,89×** |

Os seis resultados produziram o mesmo SHA-256 do áudio decodificado: `ba9269ebf07063096e1385813409a027279b674982da2dd67d6b02e159a586a7`.

É uma medição sintética, de uma execução por caminho. ALAC ainda paga a leitura dos pacotes e os passos de remux; arquivos curtos ou com outro padrão de compressão podem ter ganho menor ou nenhum ganho. A cópia permanece precisa, sem recodificar o principal para mascarar esse custo.

## Verificação final

O gate canônico final passou em `git-diff-check`, `module-map-consistency`, **580 testes sem falhas ou skips**, `lintDebug` e `assembleDebug`. O lint terminou com zero erros e 1.113 avisos no projeto. A suíte contratual do harness também passou. A suíte JVM usa o FFmpeg 8.0.1 local e executa 243 saídas válidas, além dos casos de falha/cancelamento. Evidência detalhada em `build/smartinsert_audit/harness-validation.json`; matrizes, comandos e hashes em `device-results.json`, `extra-results.json` e `benchmark-results.json` no mesmo diretório ignorado.

A prova no aparelho verifica o pipeline de produção e os binários reais. Os seletores SAF, o ciclo completo da Activity e a reprodução do MediaPlayer não receberam uma prova manual completa. O APK debug é compilado para teste; não houve publicação, assinatura de release ou atualização do app instalado.

Depois da reconexão ao Tailscale, o modelo foi confirmado como **OnePlus Ace 2 Pro** em `100.114.88.45:5555`. O APK final foi enviado e a matriz adicional ampliada passou em **86 exportações, zero falhas e zero divergências** na comparação independente do áudio decodificado com as referências contínuas. Isso inclui seleção independente de faixas ao usar o mesmo arquivo, cópia WAV com transição zero e ganho constante, as 26 curvas, as quatro curvas compostas em crossfade, prévias e diferentes profundidades PCM/ALAC/FLAC. Os três novos cenários de seleção/cópia também confirmaram `partial=true`.

A evidência anterior de 72 casos foi preservada em `extra-results-prior72.json`; `extra-results.json` e `extra-test-final.txt` registram a rodada final de 86. Após trazer os resultados, a pasta exclusiva `/data/local/tmp/sig_smartinsert_20261007` foi removida do Ace, com modelo e caminho absoluto verificados antes da limpeza e ausência confirmada depois. Nenhum comando foi enviado ao OnePlus 15. Não resta rodada adicional de pipeline ou limpeza remota pendente.
