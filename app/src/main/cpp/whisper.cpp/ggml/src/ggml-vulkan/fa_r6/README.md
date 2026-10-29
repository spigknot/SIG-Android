# fa_r6 — variante FA compacta (fonte persistida, R7/fase A)

Esta pasta guarda a FONTE GLSL da variante FA compacta do whisper (a que roda no
Adreno 740 do PIPA/PJA110 quando a família FA genérica do ggml falha) e o
CONTROLE congelado da R6. Regras:

- `fa_r6.comp` — fonte MANTIDA. **R11/fase C (C2-fix, 2026-10-15): o define
  `MANUAL_XOR_REDUCE 1` fixa a redução MANUAL (soma em árvore por
  `subgroupShuffleXor`) como default de produção** — o `subgroupAdd` do driver
  Adreno 740 apresentou miscompilação intermitente dependente de regime
  térmico/clock (decoder em loop; WER 0,2–0,9 em ~50% das execuções no calor,
  ~10% no frio). A/B no mesmo regime quente: redução atual 5/10 falhas × XOR
  0/10 (Fisher p≈0,032); CPU e FA-off imunes. Evidência:
  `build/whisper-rodada11/20261014-1104/CHECKPOINT-C2-WHISPER-RODADA11.md`.
  Lib candidata: `libsig_whisper_R11C2FINAL.so` sha
  `3d52a945ea348eb628365166a4be17105101769df48aefdcf183e770e605976a`
  (idêntica byte a byte à variante XOR compilada por `-DMANUAL_XOR_REDUCE=1`).
  Header embutido correspondente: `../ggml-vulkan-fa-r6-spv.h`.
- `fa_r6_r6_control.comp` — cópia BYTE A BYTE da fonte usada na R6/fase F
  (sha256 `9638a15b531c5ef57530ba24c44e6138395784b08baa39faa53b9839981b7b65`),
  preservada para reprodutibilidade do controle R6; NÃO editar.
- O header binário NUNCA é editado à mão: é regenerado pelo gerador
  `tools/whisper/gen_fa_r6_spv.py` (compila, valida e escreve o header de forma
  determinística); o gerador também verifica o controle R6 byte a byte
  (SPIR-V sha256 `28295ef04d9e69e41a8dfbc01992e1599c7ed0344b918bc7396caa3b57f4c5b9`,
  5472 bytes).

## Toolchain (determinismo)

- glslc do NDK 27.2.12479018 (`shader-tools/windows-x86_64/glslc.exe`);
- flags: `-fshader-stage=compute --target-env=vulkan1.2 -O`;
- validação: `spirv-val` (mesmo NDK);
- variante XOR (diagnóstico): `-DMANUAL_XOR_REDUCE=1`.

Exemplo (regera + verifica, falha se divergir):

    python tools/whisper/gen_fa_r6_spv.py --check

## Contrato de layout (o que o shader ASSUME; o host valida antes do dispatch)

- Q f32 `[64, N, heads, batch]`, linhas contíguas (`nb0=4`), `nb1/4` exato;
- K/V f16 `[64, KV, heads, batch]`, contíguos nas dimensões usadas,
  divisíveis por 2 (`nb1/2`, `nb2/2`, `nb3/2` exatos);
- O f32 `[64, heads, N, batch]` TOTALMENTE contíguo (fórmula de índice fixa);
- máscara f16 densa `[KV, N, nem2, nem3]` com `nem2 ∈ {1, heads}` e
  `nem3 ∈ {1, batch}` (broadcast suportado); índice em `uint`, máximo < 2^31;
- push constants: 128B (mesmo layout do `flash_attn_base.glsl`), todos os
  valores usados cabem em u32 (o host valida ne/nb), grid = (N, heads, batch)
  dentro de `maxComputeWorkGroupCount`;
- exigências do CRIADOR da pipeline: `subgroup_size=64`, `subgroupSizeControl`
  com 64 em `[minSubgroupSize, maxSubgroupSize]`, `computeFullSubgroups`.
