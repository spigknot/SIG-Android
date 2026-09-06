# Granite 4.1 NAR — ferramentas de exportação/validação (experimentos)

Ferramentas retomáveis para o experimento Granite Speech 4.1 2B NAR → ONNX
estático → QDQ U16/U8 → R2. Específicas do 4.1 NAR (NÃO são os scripts do
Turbo 5.0 — não substitua cegamente).

## Contrato dos três wrappers (docs/plano-acao-granite-4.1-nar-qnn-20260829.md §9)

| Grafo | Entrada | Saída |
|---|---|---|
| encoder | `input_features` f32 `[1,T,160]`, T múltiplo de 200 | `encoder_bpe_logits` `[1,T/4,100352]`; `multilayer_features` `[1,T,4096]` (camadas 4,8,12,-1) |
| projector | `multilayer_features` f32 `[1,T,4096]` | `audio_embeds` `[1,ceil(T/15)*3,2048]` |
| LLM | `inputs_embeds` `[1,S,2048]`; `position_ids` i64; `attention_mask` `[1,S]` | `logits` `[1,S,100352]` (is_causal=False) |

Constantes imutáveis: `blank_token_id=100257`, `vocab=100352`, hidden 2048,
`embedding_multiplier=12`, downsample 5, block 15, min edit length 8.
Buckets: `T ∈ {200,400,800,1200,1600,2000}`; `S ∈ {64,128,256,512,768,1024,1408}`.

## Sequência executável (cada passo retomável; estado em `state/run-state.json`)

```text
# 0. ambiente e fonte imutável (revisão a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2)
python setup_env.py        --work-dir E:/SIG-granite-nar-lab/<id>
python download_source.py  --work-dir E:/SIG-granite-nar-lab/<id> --resume

# 1. referência PyTorch (WAV oficial IBM) → golden + texto
python run_reference.py    --work-dir E:/SIG-granite-nar-lab/<id> --resume

# 2. piloto float (gate): encoder T=200, projector T=200, LLM S=64
python export_static.py    --work-dir E:/SIG-granite-nar-lab/<id> --stage pilot --resume
python inspect_onnx.py     --work-dir E:/SIG-granite-nar-lab/<id> --all
python validate_float.py   --work-dir E:/SIG-granite-nar-lab/<id> --resume

# 3. batch float (só se o piloto passar)
python export_static.py    --work-dir E:/SIG-granite-nar-lab/<id> --stage batch --resume

# 4. corpus de calibração (FLEURS 5 idiomas) + captura encadeada
python build_calibration_corpus.py --work-dir E:/... corpus --per-language 20
python capture_chained_calibration.py --work-dir E:/...

# 5. QDQ U16/U8 (só depois do text_gate; decisão 30/08: trio piloto primeiro)
python text_gate.py         --work-dir E:/... --resume
python quantize_qnn.py     --work-dir E:/... --target enc:200
python quantize_qnn.py     --work-dir E:/... --target proj:200
python quantize_qnn.py     --work-dir E:/... --target llm:64
python validate_qdq.py     --work-dir E:/... --target enc:200
python validate_qdq.py     --work-dir E:/... --target proj:200
python validate_qdq.py     --work-dir E:/... --target llm:64

# 6. empacotamento e publicação (experiments/<id>/ apenas)
python build_experiment_manifest.py --work-dir E:/...
python build_validation_status.py   --work-dir E:/...   # validation-status-v2.json
python publish_experiment_r2.py     --work-dir E:/...
```

Ferramentas novas (30/08):
- `text_gate.py` — gate end-to-end POR BUCKET: mesmo input estático [1,T,160]
  para PyTorch e ONNX; amostras que CABEM no bucket; recorte do LLM
  `logits[valid_audio:S_real]` (nunca o padding); referência PyTorch por bucket;
  ≥5 amostras variadas (PT/EN/ES) para T=400/800; status por amostra e bucket:
  `passed` | `passed-with-numeric-warning` | `needs-review` | `failed`.
- `validate_qdq.py` — QDQ CPU vs float (max_abs/max_rel/NRMSE/cosseno, top1,
  texto final) por grafo/bucket.
- `build_validation_status.py` — gera `validation-status-v2.json` separado do
  manifest imutável, com status por bucket e os mesmos hashes. Buckets falhos
  são omitidos do futuro manifest de candidatos, mas os artefatos ficam como
  evidência em `experiments/`.
- `quantize_qnn.py` agora cobre o TRIO completo (enc/proj/llm) com calibração
  encadeada real (features → multilayer → audio embeds + slots).

Retomada: todos os scripts aceitam `--resume` e pulam etapas `passed` em
`state/run-state.json`; arquivos completos (tamanho+SHA) não são rebaixados.
Erros retornam exit code ≠ 0 e registram o passo como `failed`.

## Testes

```text
python -m pytest tools/granite/nar/test_nar_tools.py -q
```

## Regras douradas

- Nunca publicar em `models/granite/4.1-nar/` (raiz) — só em
  `models/granite/4.1-nar/experiments/<experiment-id>/`.
- Sem telefone: `context-ready` no máximo; nunca `npu-approved`;
  `phone_validation=false` no manifesto.
- Falha de ONNX checker/ORT/paridade ⇒ artefato NÃO sobe (role `diagnostic`
  apenas para relatórios pequenos).
