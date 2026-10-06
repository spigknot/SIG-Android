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

## LIMITACOES DECLARADAS
- "Q4 mais longo" NAO executado: a UI do Texto (campo de entrada) parou de
  aceitar input de texto via `adb shell input text` apos as primeiras rodadas
  (multiplas tentativas com quoting/UI realocada produziram texto baguncado;
  limpeza e redigitacao nao convergiram). Medicao de performance longa fica
  para janela proprio (ideal 3 reps, conforme o desenho). Os smokes CURTOS
  nao distinguem o ganho do cache (esperado: ganho aparece em pedidos longos).
- Tempos sao de poll (~3s de granularidade) — nao usar como medida fina de
  performance; residentes para "nao quebrou / saida igual".
- x86_64 nao testado em runtime (sem emulador autorizado).

## STATUS DE ACEITE (amostra)
- Candidato OpenCL (guard+cache) CARREGA, RODA (Q4/Q8), NAO QUEBRA e mantem a
  saida identica ao v10 nesta amostra — ACEITO na amostra minima.
- Ganho de performance nao medido aqui (curtos) — pendente janela propria.
- Estado do device restaurado ao encontrado (v10); nada permanente.
