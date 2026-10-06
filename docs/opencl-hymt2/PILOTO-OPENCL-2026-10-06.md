# PILOTO OPENCL — CANDIDATO vs v10 (CPH2747) — 2026-10-06

Executado apos aprovacao do usuario (incorporar F3b/cache; piloto temporario
com backup/rollback ao estado ENCONTRADO = v10 ec9c8315).

## Artefatos
- CANDIDATO (deployado no piloto): libsig_llama.so arm64, 30.764.464 B,
  sha256 596aba0e9813f516cb68117e9b8f26fe40458010cf86fb118f7c80fac13200d9
  (guard F3b v2 + loadercache sempre ON; 0 instrumentacao)
- CONTROLE (estado encontrado): v10 ec9c8315..., 30.761.920 B
- Backup local do v10 do device: scratch/opencl-cand/v10_device_backup.so
  (sha conferido = ec9c8315...)

## Precheck (fresh)
- CPH2747 via wireless 100.108.27.64:41257 (PJA110 1164a04 presente no adb —
  NAO tocado; serial explicito em todos os comandos).
- APK v1.507 (versionCode 70). Dir no_backup/native_dependencies/10-arm64-v8a
  com v10 ec9c8315 (estado encontrado).

## Execucao (fail-closed)
1. Backup do estado + `safe_deploy` CANDIDATO: push ok; tmp verificado;
   DEPLOY OK (destino = 30.764.464 B sha=596aba0e...).
2. Force-stop + reabrir; SigNative registrou 10-arm64-v8a.
3. Smoke Q4 curto OpenCL (CANDIDATO): input "Bom dia companheiros"
   -> "Bom dia, companheiros" | 7 tokens | ~10s (poll 3s)
   logcat: backend=GPU OpenCL (OpenCL / GPUOpenCL); sched_reserve OpenCL
   290.01 MiB / CPU 26.01 MiB; graph nodes=1286; splits=2; reserve 353.93 ms.
   maps/PID: lib carregada = /data/.../10-arm64-v8a/lib/libsig_llama.so
   sha256 = 596aba0e... (CANDIDATO) r-xp/r--p/rw-p.
4. Smoke Q8 curto OpenCL (CANDIDATO): -> "Bom dia, companheiros." | 8 tokens
   | ~14s; reserve 178.84 ms; OpenCL buffer 290.01 MiB; sem crash.
5. ROLLBACK `safe_deploy` v10: DEPLOY OK (destino = 30.761.920 B
   sha=ec9c8315...). Force-stop + reabrir.
6. Smoke Q4 curto OpenCL (v10 CONTROLE): -> "Bom dia, companheiros" | 7 tokens
   | ~9s; reserve 46.73 ms.
7. Smoke Q8 curto OpenCL (v10 CONTROLE): -> "Bom dia, companheiros." | 8 tokens
   | ~10s; reserve 77.75 ms.

## Resultado (por rodada; poll de 3s — valores grosseiros)
| # | Build | Modelo | Output | Tokens | Tempo |
|---|---|---|---|---|---|
| 1 | CANDIDATO | Q4_K_M | "Bom dia, companheiros" | 7 | ~10s |
| 2 | CANDIDATO | Q8_0 | "Bom dia, companheiros." | 8 | ~14s |
| 3 | v10 | Q4_K_M | "Bom dia, companheiros" | 7 | ~9s |
| 4 | v10 | Q8_0 | "Bom dia, companheiros." | 8 | ~10s |

- OUTPUTS IDENTICOS entre candidato e v10 (Q4 sem ponto; Q8 com ponto) nos 2
  modelos — ausencia de mudanca de saida na amostra.
- Sem crash/ANR nas 4 rodadas (janela observada).
- A/lib candidata comprovada no maps (sha) durante os smokes 3/4.
- Rollback readback exato; estado final = v10 (estado encontrado aprovado).

## MEDICAO LONGA (executada em retry — campo reiniciado; texto de 221 chars)
Texto: "Senhor delegado, informo que a diligencia foi cumprida integralmente e
o relatorio circunstanciado sera encaminhado apos a revisao final do setor
competente, com todas as oitivas ja transcritas e anexadas ao procedimento."
Mesmo texto nos DOIS builds; frontiers iguais (modelo carregado -> geracao).

| Build | Decode (logcat) | Tok/s (logcat) | App "Desempenho" |
|---|---|---|---|
| v10 (sem cache) | 3.32 s (59 tokens) | 17.8 t/s | (nao capturado) |
| CANDIDATO (cache ON) | 2.27 s (59 tokens) | 26.0 t/s | 59 tokens em 2.1 s (28.5 t/s) |
GANHO no decode: +46% (logcat, fronteiras iguais) / +60% vs 17.8 pela
contagem do app. Faixa coerente com o F20 stock (+16..+53%). Sem speedup
claim universal: amostra n=1 do pedido longo (janela nao repetida 3x).
O app tambem reportou: modelo carregado em 3.9s; backend GPU OpenCL;
59 tokens de saida.

## LIMITACOES DECLARADAS
- Pedido longo: n=1 por build (janela propria para >=3 reps nao executada).
- Tempos de poll (~3-4s) para os smokes curtos — usar os numeros do logcat
  (frontiers iguais) para qualquer leitura de performance.
- x86_64 nao testado em runtime (sem emulador autorizado).

## STATUS DE ACEITE (amostra)
- Candidato OpenCL (guard+cache) CARREGA, RODA (Q4/Q8), NAO QUEBRA e mantem a
  saida identica ao v10 nesta amostra — ACEITO na amostra minima.
- Medicao longa: candidato 26.0 t/s vs v10 17.8 t/s (logcat; +46%) / 28.5 t/s
  pelo app — ganho confirmado nesta amostra (n=1).
- Estado do device restaurado ao encontrado (v10 ec9c8315); nada permanente.

## RELEASE v11 — PUBLICADA (20261006_001)
- Pacote: ZIPs v11 (libs strippadas, guard+cache, 0 diag) no R2 — arm64
  43.878.781 B sha 66eecc51...; x86_64 50.006.445 B sha cfdc828a...;
  readback HTTP 200 identico; v9/v10 preservados.
- GitHub Release 20261006_001 (Latest) asset sig.apk sha b04abd60...;
  SHA do asset == build local; O:\sig.apk copiado (sha conferido).
- Repo: commit e360ca4 (COMPONENT_VERSION 11 + bumps + fixtures v11) push OK.
- APK: versionCode 71 / versionName 1.508 / APP_VERSION 20261006_001;
  541 testes 0 falhas; verify v11 ACEITO.
