# Handoff — Granite 4.1 NAR (resumo retomável para o agente "cérebro")

**Data:** 31/08/2026 · **De:** agente operador mecânico · **Para:** agente "cérebro" (decisões)

---

# ATUALIZAÇÃO — 13/09/2026 (rodada QDQ U16 + NPU diagnóstica)

**experiment-id:** `nar-qnn-lab-rebuild-20260910`
**status geral:** `PARTIAL` — F5 (QDQ para HTP) **fechada** na geração e paridade CPU; F6 e F8 dependem do aparelho.
**work-dir:** `D:\SIG-granite-nar-lab-rebuild` ⚠️ (o handoff de 31/08 aponta `E:\SIG-granite-nar-lab\...`; por
ordem do usuário de 10/09 **o E: não é mais escrito** — tudo migrou para D:)
**aparelho:** OnePlus CPH2747 (SM8850, Android 16, arm64-v8a) — **desconectado** nesta rodada

## 1. O que esta rodada decidiu (duas hipóteses do plano caíram)

| hipótese do plano | veredito | evidência |
|---|---|---|
| os 16 `Einsum` bloqueiam a NPU | **REFUTADA** | o QNN compila os `Einsum`; o culpado é o op `Shape` (`qnn_node_group.cc:47 IsSupported`) |
| a NPU compensa | **em fp16 não** | NPU 49.614 ms vs CPU 45.774 ms — mas fp16 é o pior caso do HTP |

## 2. Artefatos QDQ (novos)

6 encoders com **ativações QUInt16 + pesos QUInt8**, opset 21, 2574 nós, `Q=456 DQ=656`, 1,17 GB cada.
Pacote `pacote-u16/` com **pesos compartilhados**: 1 `.data` de 1.178.567.552 B (sha `70fff8814a1e8d41`)
+ 6 grafos de ~1 MB. Dedup: `7.042.391.640` → `1.178.562.036` B (16,7%).
Nomes idênticos aos que o app monta (`GraniteNarEngine.kt:233`) — testável sem alterar o app.

## 3. Paridade (critério = TEXTO, não cosseno)

| bucket | texto exato | delta CER | pioras | U8 (comparação) |
|---|---|---|---|---|
| t0200 | **6/6** | **+0,00000** | 0 | 6/6 |
| t0400 | **5/6** | **+0,00000** | 0 | 3/6 |
| t0800 | 4/6 | −0,00207 | 1 | **1/6** |
| t1200 | **5/6** | **+0,00000** | 0 | **3/6** |
| t1600 | 4/6 | +0,00222 | 1 | — |
| t2000 | 4/6 | −0,00222 | 0 | — |

Pior caso absoluto **+0,0022**. Divergências são pontuação e bordas (conteúdo idêntico).
Erro nas saídas (áudio de teste): cosseno **0,99905** (logits) / **0,99202** (multilayer).

## 4. Etapas que FALHARAM e o primeiro erro de cada

| etapa | primeiro erro | resolução |
|---|---|---|
| `get_qnn_qdq_config` | módulo `qnn_quantizer` inexistente (nem no fork 2.6) | parâmetros diretos no `quantize_static` |
| QDQ no modelo fp16 | `Type Error: QuantizeLinear bound to different types (float16/float)` | conversão para fp32 |
| salvar fp32 | `EncodeError: Failed to serialize proto` (limite 2 GB) | external data |
| `quant_pre_process` no fp32 | mesmo `EncodeError` (salva sem external data) | shape inference em memória |
| opset 21 "na mão" | `Unrecognized attribute: axes for ReduceMax` | conversão cirúrgica |
| opset 21 (2ª) | `Split: Neither 'split' input nor 'num_outputs'` | incluir os `Split` |
| cache emprestado entre buckets | texto 1/4, CER **piorando** +0,023 | um cache por bucket |
| U8 nas ativações | t0800 +0,01453 (4 pioras) | **U16** |
| saturação por ativação | matching devolveu 0 pares (7 tentativas) | medido por saída; limite documentado |

## 5. Bytes únicos/duplicados

- `.data` compartilhado: **1.178.567.552 B** (único)
- somando sem dedup: **7.042.391.640 B** → economiza **5,86 GB**
- pacote total: **1,10 GiB**

## 6. Upload R2 (seção 16)

**Publicado:** o piloto QDQ U16/U8 aprovado (prioridade 3 da seção 16) —
`models/granite/4.1-nar/experiments/nar-qnn-lab-rebuild-20260910/pacote-u16/`

- **manifesto público:** https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/4.1-nar/experiments/nar-qnn-lab-rebuild-20260910/pacote-u16/manifest.json
- 8 objetos: 1 `.data` (1.178.567.552 B) + 6 grafos + `manifest.json`
- **8/8 com HTTP HEAD público 200**; `manifest.json` enviado **por último** (item 8)
- prefixo próprio da rodada — a regra 6 (não sobrescrever conteúdo diferente) bloqueou corretamente
  o envio ao `pacote-20260911/` da rodada anterior
- `reports/r2-upload.jsonl` atualizado, sem segredos (item 12)

**NÃO enviado:** o pacote de produção `models/granite/4.1-nar/v2` não foi tocado (item 11).

## 7. Gates (13/09)

- Android: `testDebugUnitTest` + `lintDebug` + `assembleDebug` — verdes na última mudança (`d84e68a`)
- Python: 51 testes das ferramentas + 58 das minhas — passando
- Repo: HEAD `a79ce03` = `origin/main`; **nada desta rodada foi commitado** (vive no lab)

## 8. Perguntas que exigem decisão do "cérebro"

1. **Adotar §13 por CER?** Recomendo sim: 6/6 buckets com CER entre −0,00222 e +0,00000; o critério de
   texto exato reprova por uma vírgula e é inadequado para int8.
2. **F6 vale o investimento?** O contexto pré-compilado resolve os 165 s de preparação da NPU — mas só
   importa **se** a NPU for de fato mais rápida. Sugiro **medir int8 na NPU primeiro**.
3. Publicar o pacote U16 em `experiments/` agora, ou após o teste no aparelho?

## 9. Cinco próximos comandos exatos (quando o telefone voltar)

```bash
cd /d/SIG-granite-nar-lab-rebuild
adb devices
bash scripts/testa_pacote_u16_no_aparelho.sh
adb logcat -d | grep -a NAR_BENCH_JSON
# comparar com o baseline fp16: CPU 45.774 ms | NPU 49.614 ms (bucket 400)
```

## 10. Relatórios desta rodada

- `reports/relatorio-cerebro-20260913.md` (no lab) — resultados, falhas, hipóteses refutadas
- `reports/final-summary.json`, `run-state.json`, `artifact-index.json`,
  `validation-summary.json`, `r2-upload.jsonl`, `resume-command.txt` (§19)
- `reports/cobertura-qdq.json` (§15-item 7)

---

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

## Verificação 06/09 — tarefa curta do remoto (text smoke + sensibilidade)
- Arquivos confirmados no R2 (HEAD 200 + GET íntegro, conteúdo == relatado):
  `diagnostics/qdq-text-equality-s0064.json` (`text_equal_all=false`, 5/5 divergentes)
  e `diagnostics/llm-sensitivity-s0064.json` (baseline cos 0,6331; melhor bloco
  fp32 L04–L07 cos 0,7089 ganho +0,0757; pior L36–L39 cos 0,5517 ganho −0,0815).
- **Veredito piloto §13: NÃO PASSA** (`text_equal=false` 5/5) → batch QDQ segue
  **não-candidato** (`needs-precision-review`); §17 mantém o bloqueio de lote.
- **Causa B confirmada como específica da chain do remoto**: nosso export local
  usa `model.config.encoder_layer_indices` ([4,8,12,-1], verificado em
  `tools/granite/nar/export_static.py:46`, `run_reference.py:115`,
  `text_gate.py:198`) — nossos artefatos float seguem íntegros. O viés
  `hs[-4:]` mora na re-exportação do remoto, não na nossa.
- Proposta do remoto (re-export [4,8,12,-1] + pools + re-quant + smoke, ~1 dia)
  é o caminho correto; anomalia L36–L39 deve ser esclarecida antes de qualquer
  fp32 parcial no fim da rede. Evidência:
  `E:\SIG-granite-nar-lab\nar-qnn-20260829-223957\\reports\verify-remote-20260906.json`.

## Verificação 08/09 — re-export fiel do remoto (`nar-qnn-remote-20260906-194608`)
- Manifest verificado no R2 (51 entries, toolchain ORT 1.29.0, source_revision
  hash, HEAD 200): re-export fiel dos 6 encoders, GATE ZERO 6/6, text-gate
  float 5/5 multilíngue, projectors 6/6 mantidos (cos 0,9995–0,9999), pools
  fiéis (74 áudios únicos, dedup por caminho).
- **Piloto QDQ s0064: FALHA** — 0/6 texto idêntico (float real, QDQ vazio/"000";
  cos 0,40–0,52, top1 0,74–0,79); smoke E2E QDQ 0/5 (CER 1,0); sensibilidade
  10 blocos 0/16 (L04–L07 melhora cos mas não recupera texto; L36–L39 piora).
- **Veredito §13: NÃO PASSA** → `needs-precision-review`; batch segue
  não-candidato (§17 mantém o bloqueio). Causa B confirmada como específica
  da chain do remoto; float local íntegro.

## Verificacao 08/09 - rodada hibrido + SmoothQuant (nar-qnn-remote-20260907-224034)
- Experimento verificado no R2 (7 objetos, manifest HEAD 200, diagnosticos GET integros, conteudo == relatado).
- Fase A (hibrido enc-fp32 + LLM-QDQ s0064): FALHA 0/5 - float com texto real (CER 0,15-0,31), QDQ vazio em 100% (CER 1,0). Hipotese B-3 descartada: a causa esta dentro do LLM quantizado.
- Fase B (SmoothQuant a0.3/a0.5/a0.7): FALHA 0/16 cada - a0.5 colapsa para repeticao, a0.3 da vazio/pontuacao. AWQ segue nunca tentado.
- Veredito S13: NAO PASSA (terceira confirmacao independente) -> batch nao-candidato, needs-precision-review definitivo p/ QDQ estatico u16u8 neste LLM; S17 mantem o bloqueio de lote.
- Restam, em ordem: (1) AWQ nos lineares do LLM; (2) fp32 parcial EMBARCADO L04-L07 como artefato; (3) bundle hibrido encoder-fp32 como candidato explicito. Sem telefone, teto = context-ready.

## Verificacao 09/09 - AWQ s0064 (`nar-qnn-remote-20260908-192009`)
- Experimento verificado no R2 (10 objetos, manifest HEAD 200, diagnosticos GET integros, conteudo == relatado). Nenhum artefato candidato publicado, so evidencias.
- Fase A (hibrido enc-fp32 + LLM-QDQ): FALHA 0/5 - float com texto real (CER 0,15-0,31), QDQ vazio em 100%. Hipotese B-3 descartada definitivamente.
- Fase B (AWQ a0.25/a0.5/a0.75): FALHA 0/16 cada; combinada AWQ+L04-L07 fp32: 0/16 + 0/5 multiligue (melhor cos 0.556 < QDQ puro 0.633).
- Veredito S13: NAO PASSA (quarta confirmacao independente) -> needs-precision-review DEFINITIVO p/ QDQ estatico u16u8 neste LLM; S17 mantem o bloqueio de lote.
- Restam, em ordem: (1) GPTQ; (2) quantizacao dinamica; (3) revisao de arquitetura/precisao. Proxima rodada so com ordem expressa.

## Migracao 09/09 - drive E: falhando, work-dir agora D:/SIG-granite-nar-lab-rebuild
- E: declarado falhando pelo usuario; uso do E: interrompido imediatamente. Nenhum processo escrevia no E: no momento.
- Preservados no D: 22 reports + 8 scripts auxiliares (190 KB total). Modelos multigigabyte NAO copiados (serao recuperados do R2 ou regenerados).
- Diagnosticos finais publicados no R2 (prefixo do experimento, diagnostics/, nomes novos sem sobrescrita, HeadObject + HTTP 200): final-summary-20260909.json, run-state-20260909.json, verify-remote-20260906.json, relatorio-geral-cerebro-20260909.txt.
- Proximas tarefas usam --work-dir D:/SIG-granite-nar-lab-rebuild.

## Verificacao 10/09 - QUANTIZACAO REABERTA (4-bit weight-only) + encoder pronto para NPU
**Dois erros de MEDICAO meus invalidaram o veredito anterior de "linha encerrada":**
- Igualdade exata de texto e metrica binaria: reprova a amostra por um caractere. Com artefato ONNX real, corpus de 82: `int8/4-bit` dava 41/82 exato (parecia reprovacao) mas **CER 0,0324 vs float 0,0323** (delta +0,0001). Pareado: 14 melhoram, 48 empatam, 20 pioram.
- A referencia NAO pode ser a saida do proprio modelo: medido, `ctc_ids` identicos 5/5, LLM fp16 == fp32 5/5, e **fp32 puro comete os mesmos erros** em palavras raras (`wifi door bell` -> `wi doorbell`, `inland waterways` -> `land`). Isso descarta fp16 como causa e mostra que auto-referencia pune a quantizacao por erros que o float ja tem.

**Metodo que funciona: `MatMulNBits` RTN weight-only, bits=4, block=128, SIMETRICO, SEM calibracao.**
- LLM de PRODUCAO quantizado no contrato real do app (2 inputs, S dinamico, sem padding): **CER 0,0338 vs 0,0323 (x1,05)**, 59 empatam / 7 melhoram / 16 pioram, **x4,3 mais rapido, x3,9 menor** (841.617.408 B vs 3.263.500.288 B). Contrato `inputs_embeds`+`position_ids` preservado.
- Contra os pilotos do PC auxiliar, na MESMA metrica deles: nosso CER 0,0151 vs GPTQ 0,1562 e dinamica INT8 0,2384, com zero corrupcao de prefixo. **O trabalho deles nao estava errado** - os metodos deles degradam mesmo; o que nao se sustentava era generalizar que nenhum serviria.
- Contraintuitivo: a calibracao PIOROU (GPTQ 0,1562 vs RTN puro 0,0151).
- Armadilha medida: `MatMulNBitsQuantizer` so usa o parametro `bits` quando `algo_config is None`; passar `RTNWeightOnlyQuantConfig()` (sem campo `bits`) faz o default de 4 valer e o pedido ser **silenciosamente ignorado** - uma rodada rotulada "8 bits" saiu byte-identica a de 4 bits. Agora o script ABORTA se os bits do grafo nao baterem.

**Bloqueador da NPU identificado e removido (encoder):**
- Documentacao oficial: `Einsum` e `Mod` existem **so no fork** `onnxruntime-qnn`, NAO no ORT mainline que o app usa. O encoder tem **16 `Einsum`** de atencao relativa -> cada um vira no de CPU EP -> e exatamente a rejeicao de 29/08 ("encoder rejeitado porque ha nos atribuidos ao CPU EP").
- `tools/granite/nar/einsum_to_matmul.py` reescreve como `Split+Reshape+Transpose+MatMul+Concat`: **16/16, zero restantes**, contrato de I/O IDENTICO, prova algebrica em numpy/fp16, **tokens CTC identicos 6/6**, texto 5/6 com **delta CER -0,0068 (o convertido e MELHOR)** e custo de tempo neutro (174,6s -> 176,1s).
- Achado novo: `OptLevel.NO_OPT` (GraniteNarEngine, **nas sessoes ACELERADAS** - a rota de CPU usa `BASIC_OPT`) **desliga o constant folding** - 690 `Constant` e 49 `Shape` no encoder chegam ao particionador do QNN. `tools/granite/nar/fold_constant_subgraphs.py` pre-dobra offline. Medido: dobra propria e **BIT-EXATA** (projector, max|diff| = 0.0); `ORT_ENABLE_BASIC` **NAO e** (rel 1,5e-2).
- O projector NAO tem bloqueador de op: `Erf` casa a fusao documentada `Div(sqrt2)->Erf->Add(1)->Mul->Mul(0.5)` -> `QNN_OP_GELU`, e `Mod` era calculo de shape (`2000 % 15` sobre dois Constants).

**Encoder pronto para a NPU — DUAS variantes** (rodada de 11/09, `reports/encoder-estatico-20260911.md`). As duas tem `Einsum=0` e um unico `encoder-pesos.data` compartilhado (1,01 GiB, pesos conferidos **472/472 initializers byte a byte**); o ARQUIVO `.data` difere do publicado em +352 B (initializers de shape da conversao) e **nao e intercambiavel** — vai junto no push. Roteiro: `scripts/testa_encoder_npu.sh <wav> --pacote <variante>`.

| variante | dims de saida | o que isola |
|---|---|---|
| `pacote-teste-npu-v2` | SIMBOLICAS | efeito **so** do `Einsum` |
| `pacote-npu-estatico` | **FIXADAS** | candidato completo (o plano exige "ausencia de dimensoes dinamicas") |

As duas correcoes do plano para o encoder, medidas: (1) `Einsum` 16 -> 0 (existe so no fork `onnxruntime-qnn`); (2) dimensoes de saida simbolicas (`Cast..._dim_N` de um shape-inference que parou num `Cast`) -> valor **medido** na inferencia real, com **6/6 buckets bit-exatos** e verificacao independente (estrutura + paridade empirica) em `reports/estatico-verificado.json`. **Se a estatica passar e a simbolica falhar, a dim simbolica era o bloqueio; se as duas falharem, o bloqueio e outro** (proximo suspeito: o LLM com `sequence_length` dinamico, que NAO foi estaticado — decisao consciente).

**Nao exige tocar no `GraniteNarEngine`**: o `GraniteNarSmokeTestActivity` (app/src/debug) aceita `backend`/`require_full_acceleration`/`audio_path` por intent e recusa fallback silencioso. (O diretorio `pacote-teste-npu/`, de formato antigo e pesos embutidos, esta OBSOLETO — ver `reports/proposta-limpeza-20260911.md`.)

**Repo (commits `99077b5`, `b48cf43`, sem bypass de hook):** `common.build_insertion_slots()` (blank intercalado - estava DUPLICADO em 3 arquivos), `common.cer()`, `common.quantized_bits_check()`, + as duas ferramentas novas. Testes **24 -> 43**. Gates: pytest 43 passed, `check-module-map` PASS (72/72), harness exit 0, `testDebugUnitTest+lintDebug+assembleDebug` BUILD SUCCESSFUL. `docs/qairt-status.md` atualizado.

**R2:** prefixo NOVO `models/granite/4.1-nar/experiments/nar-qnn-lab-rebuild-20260910/` - 19 objetos, 374.028 B, manifest publico HTTP 200, `npu_approved=false`. Prefixos anteriores conferidos INTACTOS (405 e 8 objetos). So diagnosticos pequenos; nenhum modelo publicado.

**PENDENTE (decisao do gerente):**
1. Limpeza de disco: `reports/proposta-limpeza-20260910.md` - 22,4 GB recuperaveis sem risco (14,81 GB de `.data` duplicado entre shells - esperado, os pesos nao dependem de `S`; 7,54 GB de intermediarios regeneraveis; 70 MB de corpus defeituoso). Nada removido sem aprovacao.
2. Criterio S13: oficializar **CER <= CER float + 0,005** e zero amostras com CER > 0,30, deixando a igualdade exata como informativo.
3. Validacao na NPU: so com o aparelho. Tudo continua `context-ready`, nada `npu-approved`.

## Verificacao 11/09 - escolha da variante do LLM na UI + pacote v2 (pesos compartilhados)

**Pedido do gerente:** *"deixar o usuario escolher, mas colher os dados do teste para mostrar na tela"* (modelo Whisper: tiny/small/medium/turbo).

**MEDIDO (82 amostras FLEURS, 5 idiomas, contrato REAL do app: 2 entradas, `S` dinamico, sem padding; ORT CPU):**

| variante | `.data` | velocidade | CER global | pt-BR | texto = float | > 0,30 |
|---|---|---|---|---|---|---|
| fp16 (maxima) | 3,3 GB | — | 0,0323 | referencia | — | 0 |
| **int8b (equilibrado)** | **1,7 GB** | **2,4x** | **0,0321** | **+0,0012 PASSA** | **75/82 (91,5%)** | **0** |
| int4b (leve) | 842 MB | **4,3x** | 0,0338 | +0,0054 EXCEDE | 50/82 | 0 |
| ~~int2b (minimo)~~ | ~~434 MB~~ | 3,0x | **0,7946** | +0,7850 EXCEDE | **0/82** | **82/82** |

- **O 8-bit resolve o dilema qualidade x tamanho**: passa em TODOS os 5 idiomas (folga de 4x no limite em pt-BR), 76 de 82 amostras empatam EXATAMENTE com o float, CER global levemente melhor. 35% menos download, 2,4x mais rapido.
- **O 2-bit foi REPROVADO**: nao e qualidade menor, e texto destruido (0/82, CER 24,6x, 82/82 acima de 0,30). O artefato e tecnicamente valido (`MatMulNBits={2: 281}`, carrega, contrato certo) e 3x mais rapido — por isso fica registrado no codigo (`DOIS_BITS_REPROVADO`) com os numeros, para ninguem tentar de novo sem saber.

**UI:** botao na linha do modelo (so com o NAR) -> dialogo com tamanho, velocidade e efeito na qualidade de cada opcao. Trocar de variante abre o download se o par nao estiver no aparelho. Download SOB DEMANDA (baixar as tres custaria 6,9 GiB, pior que os 4,74 GiB de hoje).

**Escolha de bucket:** o app usa o MENOR que caiba. Medido: o padding ate T=2000 degradava a transcricao (1 piora / 7 empata / 0 melhora, com perda de palavra `inland water` -> `inlandways`) e custava 9,7x em audio curto.

**PACOTE v2 publicado** em `models/granite/4.1-nar/v2` (25 objetos, 6,92 GiB; v1 INTACTO com 836 objetos):
- pesos COMPARTILHADOS entre buckets: 472/472 initializers identicos por CONTEUDO (o exporter renumera os nomes: `onnx::Conv_3197` -> `onnx::Conv_3199`). Cada bucket extra custa ~720 KB, nao ~1,04 GB — o pacote ficou MENOR que o de bucket unico com 6 buckets.
- paridade **12/12 BIT-EXATA** (6 encoders + 6 projectors vs os originais, pesos embutidos).
- 3 variantes do LLM, cada uma com `external_data.location` casando com o nome publicado (verificado por carregamento ORT).
- **`manifest.json` agora tem sha256 de cada arquivo** (25/25) — exigencia "SHA-256 antes da ativacao" da Fase 7.

**Fase 7 no app (componentes do plano):**
- ✅ SHA-256 antes da ativacao: `GraniteNarManifest.kt` (parse + verificacao em streaming); o download confere o `.download` ANTES de renomear e aborta com mensagem clara se divergir.
- ✅ nao manter todas as sessoes abertas + cache de uma dupla encoder/projector e uma sessao LLM: `encoderPara`/`projectorPara`/`llmPara` fecham a anterior ao trocar.
- ✅ pesos/embeddings mapeados (nao copiados ao heap Java): `nar_embed_tokens.bin` via mmap (ja existia).
- ✅ remocao de versoes antigas recuperavel: `limparPacoteLegado` so apaga depois de `packageComplete`, e nunca toca em arquivo das variantes.
- ⬜ rollback para ultimo pacote aprovado / retomada de download: NAO implementado (registrado como pendente).

**Fase 0 - entregaveis que faltavam, agora presentes:** `tools/granite/nar/requirements-lock.txt` (versoes exatas Python 3.11.15 / torch 2.9.1+cpu / transformers 5.17.0 / onnx 1.22.0 / **onnxruntime 1.29.0** / numpy 2.4.6). A **matriz ORT-QAIRT nao existe e nao pode existir no PC**: o QAIRT so esta no pacote que o app baixa no aparelho — mede-se no device.

**Vacinas:** 15 testes novos (37 so no `GraniteNarEngineTest`; **352+ no total**, 0 falhas) — faixa termina no 4-bit, 2-bit fora da lista mas registrado, ids estaveis, nomes/locations do contrato do device, limpeza nao apaga variante em uso, e a integridade SHA-256 (parse tolerante, digest conhecido, arquivo alterado reprova, nao-listado nao bloqueia).

**Ferramentas de medicao no aparelho (o plano ja previa a oficial):**
- `scripts/run-granite-nar-adb-benchmark.ps1` (do repo, Fase 8) = caminho OFICIAL: build+install, warmup, execucoes medidas, logcat, dumpsys, bateria, thermal, JSONL.
- `scripts/testa_encoder_npu.sh` = testa a hipotese dos `Einsum` na NPU (troca os 6 grafos convertidos e chama o oficial; os pesos nao mudam).
- `scripts/testa_variantes_celular.sh` = compara fp16 / 8-bit / 4-bit com o mesmo audio.

**Cinco proximos comandos exatos quando o telefone voltar:**

**Audios prontos em `D:\audios\`** (pt-BR, 16 kHz mono, com transcricao de referencia FLEURS em
`audios.json` — `scripts/prepara_audios_teste.py`):

**Gravacao de voz propria (opcional, mas cobre o que o corpus NAO cobre):** os textos para gravar
estao em `D:\SIG-granite-nar-lab-rebuild\textos-para-gravar.txt`; grave em `D:\audios\originais\`
(qualquer formato — `scripts/processa_audios_gravados.py` converte para 16 kHz mono e valida) e o
que cada um acrescenta:

| gravacao | o que cobre e nada mais cobre |
|---|---|
| `longo-taguai.wav` (~35 s) | **bucket 1600/2000** — o app escolhe o MENOR bucket, entao nenhum audio curto chega la; sem ela a selecao de bucket nao e exercitada de ponta a ponta |
| `curto-taguai.wav` | a palavra **"Taguaí"** (nenhum corpus tem; o modelo ja entregou "Itaguaí") |
| `medio-ocorrencia.wav` | vocabulario do trabalho + numeros por extenso |
| `curto-ruido.wav` (opcional) | robustez a ruido de fundo — nenhum audio do lab tem ruido |

⚠️ Limite real: `frames <= 2000` e `frames = amostras/320` -> **40 s** a 16 kHz. Acima disso o app
rejeita o audio INTEIRO (nao trunca). O script de processamento corta com aviso se passar.

| arquivo | duracao | frames | bucket | custo/rodada no PC |
|---|---|---|---|---|
| `nar-curto-7s.wav` | 7,1 s | 354 | **400** | ~149 s |
| `nar-curto2-7s.wav` | 7,3 s | 366 | 400 | ~149 s |
| `nar-medio-13s.wav` | 13,3 s | 666 | 800 | ~302 s |
| `nar-longo-19s.wav` | 18,8 s | 939 | 1200 | ~516 s |

⚠️ **Use o CURTO para aceitacao.** A pergunta "a NPU aceita o grafo?" e respondida na **criacao da
sessao**, nao pelo audio longo — e um audio de 7 s custa 1/6 de um de 30 s (o benchmark multiplica
por warmup+N). O limite do app e `frames <= 2000` = **40 s a 16 kHz** (a mensagem dizia 20 s por um
bug de formula, corrigido nesta rodada).

```powershell
# 1. Instalar o APK (md5 68f5322b56aa57bdb0392232f2a36b88)
adb install -r O:\sig.apk

# 2. Baseline CPU oficial (warmup + 3 medidas + thermal/bateria/JSONL) — audio CURTO
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-curto-7s.wav -Backends CPU -WarmupRuns 1 -MeasuredRuns 2

# 3. NPU ESTRITA — variante A: so o Einsum convertido (dims de saida ainda simbolicas)
bash D:\SIG-granite-nar-lab-rebuild\scripts\testa_encoder_npu.sh D:\audios\nar-curto-7s.wav --pacote pacote-teste-npu-v2

# 3b. NPU ESTRITA — variante B: candidato completo (Einsum=0 + dims FIXADAS)
bash D:\SIG-granite-nar-lab-rebuild\scripts\testa_encoder_npu.sh D:\audios\nar-curto-7s.wav --pacote pacote-npu-estatico

# 4. Comparar as 3 variantes do LLM com o MESMO audio (fp16 / 8-bit / 4-bit)
bash D:\SIG-granite-nar-lab-rebuild\scripts\testa_variantes_celular.sh

# 5. Qualidade num caso real (audio medio, com referencia para conferir o CER)
.\scripts\run-granite-nar-adb-benchmark.ps1 -AudioPath D:\audios\nar-medio-13s.wav -Backends CPU -WarmupRuns 1 -MeasuredRuns 1 -IncludeTranscript
```

O passo 3 e o unico que responde a pergunta aberta mais importante: **os 16 `Einsum` eram o
bloqueio da NPU estrita?** Se a sessao abrir com `Handoff` completo e sem fallback, a hipotese
estava certa e o proximo trabalho e quantizar o encoder. Se voltar o "nodes assigned to CPU EP",
o bloqueio e outro e o diagnostico (secao 6 do `docs/qairt-status.md`) precisa ser refeito com
o log do aparelho.

**PENDENTE (decisao do gerente):**
1. Testar o APK no aparelho (O:/sig.apk, md5 `68f5322b56aa57bdb0392232f2a36b88`); nada foi publicado como release nem teve bump de versao.
2. Limpeza de disco: 22,4 GB recuperaveis (`reports/proposta-limpeza-20260910.md`) + 3,1 GB de lixo do experimento exploratorio. Nada removido sem aprovacao.
3. NPU: so com o aparelho. Tudo continua `context-ready`, nada `npu-approved`.
