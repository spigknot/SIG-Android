# Handoff — Granite 4.1 NAR (resumo retomável para o agente "cérebro")

**Data:** 31/08/2026 · **De:** agente operador mecânico · **Para:** agente "cérebro" (decisões)

## Identificação
- **experiment-id (float):** `nar-qnn-20260829-223957`
- **experiment-id (QDQ remoto, toolchain correto):** `nar-qnn-remote-20260831-041516`
- **status geral:** `PARTIAL` (float completo; QDQ remoto completo — 26 artefatos — mas com **lacunas de qualidade** que impedem promoção a candidato)
- **revisão fonte:** `ibm-granite/granite-speech-4.1-2b-nar @ a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2` (Apache-2.0)
- **diretório local:** `E:\SIG-granite-nar-lab\nar-qnn-20260829-223957` (source/cache/venv/exports/golden/calibration/quantized/packages/logs/reports/state)

## O que está CONCLUÍDO e verificado (com evidência)
| Item | Status | Evidência |
|---|---|---|
| Gates Android (§18) | ✅ | `testDebugUnitTest` 143/0/0, `lintDebug` OK, `assembleDebug` OK (`logs/android-gates-final.log`) |
| Fonte imutável | ✅ | revisão `a1e3416...`, 21 arquivos/4,52 GB, 0 LFS (`reports/source-manifest.json`) |
| Ambiente Python | ✅ | torch 2.9.1+cpu, transformers 5.16.1, ORT 1.29.0 (`reports/environment.json`) |
| Referência PyTorch | ✅ | texto real decodificado; T=844, ctc=44, S=257 (`reports/reference-report.json`) |
| Piloto float | ✅ | enc T=200 top1 0,98 + CTC idêntico; proj cos 0,999981 |
| Gate de máscara | ✅ | S=40/48/56: top1=1.0000 — máscara preserva prefixo |
| Batch float export | ✅ | 19/19 ONNX (enc/proj T=200..2000; llm S=64..1408) |
| Corpus calibração | ✅ | 82 WAVs, 5 idiomas, SHA-256 (`calibration/corpus-manifest.jsonl`) |
| Upload R2 float | ✅ | 26/26 objetos verificados (HeadObject + HTTP 200) |
| **QDQ remoto (ORT 1.29.0)** | ⚠️ publicado | 26 artefatos QDQ (6 enc, 6 proj, 7 llm + 7 `.data`), 16,69 GB, toolchain correto, HeadObject sha256 ✓. **Mas:** `validate-qdq-projector-t0200.json` do remoto = `investigate` (cos 0,792, max_abs 10,7) |
| **text-gate remoto** | ⚠️ cobertura fraca | passed em T=200..2000, **porém só com amostras `de_de`** — o prompt exigia PT/EN/ES em T=400/800 |
| Ferramentas Python | ✅ | 17/17 testes passando (`tools/granite/nar/test_nar_tools.py`) |

## O que está EM ANDAMENTO / PENDENTE
- **🔍 DIAGNÓSTICO (C) CONCLUÍDO — CAUSA RAIZ DO QDQ DEGRADADO (31/08, inspeção dos grafos v2):**
  Os QDQ do remoto (`nar-qnn-remote-20260831-192106`) têm **3 defeitos estruturais**:
  1. **Encoder: outputs com dimensão SIMBÓLICA** — `ctc_logits [Castctc_logits_dim_0, 100352]` e
     `multilayer_features [Cast...dim_0, 2000, 4096]` (shape inference não resolve para valor
     estático). Viola §10 ("candidato QNN não pode ter dimensão dinâmica") e o nome mudou de
     `encoder_bpe_logits` para `ctc_logits`. Explica a degradação dos encoders longos (t0800+).
  2. **LLM: PERDEU o input `attention_mask`** — o float original tem 3 inputs
     (`inputs_embeds`, `position_ids`, `attention_mask [1,64]`), mas o QDQ v2 só tem 2.
     Sem máscara, o padding corrompe a atenção bidirecional → cos 0,69–0,88 em TODOS os LLMs.
  3. **Opset 21 em vez de 17** (exigido pelo plano).
  Projectors ficaram bons (cos 0,9999) porque não têm esses padrões.
- **Ação (A):** prompt de correção para o remoto com os 3 pontos explícitos — manter
  `attention_mask` no LLM, shapes 100% estáticos nos encoders (sem dims simbólicas),
  opset 17, e re-validar com texto real (não só cos).
- **Validação no telefone** — sem aparelho; todos os QDQ são `context-ready`, nunca `npu-approved`.
- **Commit local** — pendente por design: o pre-commit do SIG exige worktree limpo em `app/src/` e `scripts/`; o harness ADB (6 modified + 3 untracked) é trabalho do usuário ainda não commitado. Quando ele commitar, rodar `git add tools/granite/nar/ docs/handoff-granite-nar-artefatos-20260830.md` + commit.

## R2 (URLs públicas)
- **Float (meu):** `https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/4.1-nar/experiments/nar-qnn-20260829-223957/manifest.json`
- **QDQ piloto (remoto, toolchain correto):** `https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/4.1-nar/experiments/nar-qnn-remote-20260831-041516/manifest.json`
- **QDQ antigo (toolchain divergente — NÃO usar como candidato):** `nar-qnn-remote-20260830-221013` (torch 2.2/ORT 1.20; mantido como evidência)

## Decisões que exigem o cérebro
1. **Aguardar o batch QDQ do remoto** (16 buckets) e validar os hashes/text-gate antes de qualquer promoção a `packages/`.
2. **Toolchain divergente do 1º trio** (`nar-qnn-remote-20260830-221013`): descartar como candidato (o 2º, com ORT 1.29.0, é o válido).
3. **T=400** (que aqui falhou no `fr_fr_1597` com texto divergente): o remoto passou T=400 com amostras de_de. **Divergência de idioma/amostra** — decidir se exige re-teste com fr_fr antes de promover T=400, ou se o gate do remoto (5 amostras) é suficiente.
4. **Promoção a `packages/`** só depois de: batch QDQ completo + validação no telefone (SM8850/HTP v81) + aceitação térmica/energética.

## Próximos comandos quando o telefone voltar
```powershell
# 1. load-only estrito do QDQ piloto (fallback proibido)
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-pt-04s.wav -Backends NPU_QNN_HTP -LoadOnly -MeasuredRuns 1 -TimeoutSeconds 1200
# 2. CPU baseline (float T=2000 atual)
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-pt-04s.wav -Backends CPU -WarmupRuns 1 -MeasuredRuns 3
# 3. GPU estrita piloto float
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-pt-04s.wav -Backends GPU_QNN -LoadOnly -MeasuredRuns 1
# 4. NPU estrita QDQ piloto (recusar fallback)
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-pt-04s.wav -Backends NPU_QNN_HTP -WarmupRuns 1 -MeasuredRuns 3 -RequireAllPassed
# 5. matriz de duração com pacote aprovado
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-pt-20s.wav -Backends CPU,NPU_QNN_HTP -WarmupRuns 1 -MeasuredRuns 3 -RequireAllPassed
```

## Retomada
- Estado: `E:\...\state\run-state.json` (todas as etapas locais `passed`; `text_gate` local `interrupted` — superado pelo remoto).
- Comando geral: cada tool aceita `--work-dir E:\SIG-granite-nar-lab\nar-qnn-20260829-223957 --resume`.
- Documentação: `docs/plano-acao-granite-4.1-nar-qnn-20260829.md` §9–§16; ferramentas em `tools/granite/nar/`.
