# Refatoração SIG — legibilidade e extração de seams (2026-09-08)

Relatório do que mudou, por quê e como foi verificado. Escrito para ser lido
por uma pessoa ou por um agente de IA que abrir o projeto depois.

## Regra que guiou o trabalho

**Preservar o comportamento do app é mais importante do que atingir uma
arquitetura "perfeita".** Toda mudança foi: extrair → religar chamadas →
compilar → testar → gate completo. Nada foi "consertado" de passagem.

## Resultado em números

| Métrica | Antes | Depois |
|---|---|---|
| Suítes de teste | 23 | 29 |
| Testes | (baseline 0 falhas) | **289, 0 falhas, 0 erros** |
| Linhas nos arquivos grandes | — | **−1.255** |
| `git diff --stat` das fontes | — | 11 arquivos, **+194 / −1.449** |
| Arquivos de produção novos | — | 7 (936 linhas) |
| Arquivos de teste novos | — | 6 (77 casos) |

## Arquivos novos (cada um com uma responsabilidade e nome explícito)

| Arquivo | Responsabilidade | Origem (duplicação) | Testes |
|---|---|---|---|
| `SttResponseParsers.kt` | Interpretar respostas REST/SSE/WS dos provedores STT (Granite, Grok, Metamuse, Alibaba, timestamps, diarização) | `RemoteSttActivity` | 29 |
| `TranscriptionReport.kt` | Relatório HTML, log de terminal, nomes de arquivo, tamanhos | 3 Activities | 13 |
| `SttAudioProbe.kt` | Sondar áudio com FFmpeg e interpretar a saída (codec, Hz, canais, bitrate) | Granite + RemoteStt | 8 |
| `LittleEndianIo.kt` | Leitura/escrita little-endian (WAV e pacotes) | Motores Granite | 7 |
| `GraniteBinarySupport.kt` | Tabela byte↔codepoint, cadeia de erro, WAV 16 kHz mono, floats | `GraniteEngine` / `GraniteNarEngine` | 12 |
| `MediaUriSupport.kt` | Nome de arquivo de URI, permissão de pasta, pedido de permissão | **8 cópias idênticas** | — |
| `MediaTypeRules.kt` | `isVideo` / `isAudio` / `isSupportedMedia` / `guessMime` / `contentMimeForUpload` | Granite + RemoteStt | 8 |

Testes novos: `SttResponseParsersTest` (29), `TranscriptionReportTest` (13),
`GraniteBinarySupportTest` (12), `SttAudioProbeTest` (8), `MediaTypeRulesTest`
(8), `LittleEndianIoTest` (7).

## Arquivos que encolheram

| Arquivo | Antes | Depois | Δ |
|---|---|---|---|
| `RemoteSttActivity.kt` | 7.222 | 6.628 | −594 |
| `GraniteActivity.kt` | 2.726 | 2.478 | −248 |
| `GraniteEngine.kt` | 972 | 848 | −124 |
| `GraniteNarEngine.kt` | 845 | 724 | −121 |
| `WhisperActivity.kt` | 2.690 | 2.598 | −92 |
| `Ffmpeg{Cut,ExtractAudio,JoinVideos,RotateVideo,InsertAudio,CleanAudio}Activity.kt` | — | — | −76 |

## Como a fidelidade foi provada

1. Baseline medido de verdade (`--rerun-tasks`): 23 suítes, 0 falhas.
2. Após **cada** etapa: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — BUILD SUCCESSFUL nas 5 etapas.
3. Comparação **função por função com `git HEAD`** (`verify_fidelity.py`):
   todos os corpos movidos são idênticos, exceto mudanças intencionais e
   visíveis (prefixo do objeto, parâmetro extra, `private`). Esse verificador
   pegou um erro real (duas funções reescritas de memória com bytes errados),
   corrigido antes de prosseguir.

Reproduzir a verificação:

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

## Comportamentos estranhos preservados DE PROPÓSITO (e travados por teste)

Não "corrigir" sem decisão explícita do dono do produto:

1. `formatAlibabaRestResponse` com `content` em array devolve o JSON cru
   (`formatAlibabaRestResponse_contentEmArray_hojeDevolveOJsonCru`).
2. `matchTranscriptions`: legenda sobrando vai para o último upload por posição.
3. `MediaTypeRules.isAudio` **não** aceita `.amr`; `FfmpegExtractAudioActivity`
   tem cópia própria que aceita (`isAudio_naoAceitaAmr` documenta o motivo).
4. `readLittleShort`/`readLittleInt` (Whisper) devolvem 0 no EOF, enquanto
   `leShort`/`leInt` (motores) lançam `EOFException` — semânticas mantidas
   separadas.
5. `extractTextDelta` / `isServerEnvelopeLine` eram código morto no hotspot;
   foram movidos e cobertos por teste, **não removidos**.

## Divergências reais NÃO unificadas (exigem decisão de produto)

- `formatTime` (11 cópias, 7 variantes), `readDuration` (8/7),
  `parseDisplayRotation`, `detectVideoCodecFamily`,
  `byteLevelCharToByte` (790 vs 650 chars entre os dois motores).
- `isVideo`/`isAudio` das Activities divergem de
  `SharedMediaIntents.isVideoMedia` (áudio com extensão `.mp4`).
- `releasePreviewPlayer` (×5) e `releaseAudioPlayer` (×3): dependem de campo de
  instância — precisam virar `fun release(player: MediaPlayer?)` antes de
  qualquer unificação.

## Não tocado

- Fluxo WebSocket ao vivo, captura de microfone/WAV, painel de diagnóstico
  (áreas de maior risco).
- `docs/handoff-granite-nar-artefatos-20260830.md` (alteração do usuário).
- `AGENTS.md`: as 6 linhas do mapa de rotas para os novos seams estão
  redigidas, mas a gravação depende de aprovação de arquivo protegido.

## Pendências

1. Registrar os novos seams no `AGENTS.md` (gravação bloqueada por
   aprovação de arquivo protegido).
2. Próximos alvos mapeados: `releasePreviewPlayer`/`releaseAudioPlayer`,
   `packageFiles`/`packageComplete`/`packageDownloadBytes` dos motores,
   fronteiras restantes do `RemoteSttActivity` (mic/WAV, diagnóstico).
