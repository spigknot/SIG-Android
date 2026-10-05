# Hy-MT2 — Isolamento de pedidos (design curto)

Escopo pequeno: um pedido de tradução não pode usar KV/posição/histórico do
pedido anterior. Sem refatoração de lifecycle/cancelamento. Sem mudar
backend padrão, pesos, template, sampler, EOG ou limite de saída.

## 1. Contrato de pedido independente

- Cada `generate(prompt)` é independente: usa só os tokens do pedido atual.
- Modelo/pesos permanecem carregados entre pedidos (sem recarga como muleta).
- Sampler é criado por pedido (já é: `llama_sampler_chain_init` em
  `llama_jni.cpp:442`); `llama_sampler_sample` já faz `accept` interno
  (llama.h:1502-1512), então não chamar `accept` extra.
- Reset não muda EOG (`llama_vocab_eos`), `max_out` (4096) nem truncamento:
  achados de EOS/is_eog são separados.

## 2. Causa (fonte, revisão vendored = autoridade)

- `generate` reutiliza `g_ctx` sem limpar memória (nenhum
  `llama_memory_clear`/`seq_rm` em `llama_jni.cpp` — grep retorna 0).
- `decode_tokens` usa `llama_batch_get_one(data, count)` com `pos=null`
  (`llama_jni.cpp:457-458`).
- `llama-batch.cpp:100`: se `pos==null`, `p0[s] = memory->seq_pos_max(s)+1`.
  Logo o 2º pedido continua posições/KV do 1º → contaminação (texto anterior
  reaparece). Isso sustenta contaminação, não prova TODA anomalia (Q8 fresco
  com recarga também gerou 4096 tokens — causa pendente, §6 do prompt).
- `TextoActivity.kt:631` só recarrega se arquivo/backend mudam; mesmo
  modelo/backend reutiliza o contexto — correto após o fix, desde que o
  JNI resete por pedido.

## 3. API real de reset (revisão vendored)

De `app/src/main/cpp/llama/include/llama.h`:
- `llama_get_memory(ctx)` → `llama_memory_t` (:570).
- `llama_memory_clear(mem, bool data)` (:727): limpa conteúdo; com
  `data=true` limpa buffers + metadados.
- `llama_memory_seq_rm(mem, seq_id, p0, p1)` (:736): remove tokens da
  sequência; remover sequência inteira nunca falha.

Escolha: `llama_memory_clear(llama_get_memory(g_ctx), true)` no início de
cada `generate`, sob o MESMO `g_mutex` que protege inferência e release.
Motivo: menor custo seguro (mantém modelo/pesos/contexto alocados);
`seq_rm` parcial deixaria janelas de posição. Recriar contexto por pedido
seria correto mas custa realloc + recompilação de grafo sem necessidade.
Não recarregar pesos por pedido como correção definitiva.

## 4. Ownership e exclusão

- `g_mutex` protege `g_model`/`g_ctx`/`g_last_error`/`g_last_stats`.
  `generate`, `loadModel`, `releaseModel`, `lastError`, etc. tomam o lock.
- O reset ocorre DENTRO de `generate`, após adquirir `g_mutex` e validar
  `g_model`/`g_ctx != null`, antes de tokenizar. Mesma exclusão que
  protege inferência e release; nenhum `UI clear` concorrente toca nativo.
- Sampler: RAII manual existente (`llama_sampler_free` em todos os
  retornos após criação). O fix preserva isso; nenhum retorno precoce após
  o reset deixa stale silencioso (ver §5).

## 5. Estados antes/depois

Antes (bug): `seq_pos_max(0)=N-1` do pedido anterior → novo prefill começa
em `N`, KV antigo participa da atenção.
Depois (fix): `llama_memory_clear(..., true)` → `seq_pos_max` volta a
vazio → primeiro `llama_decode` do novo pedido começa em 0, tokens usados
restritos ao pedido atual (verificado por `seq_pos_max` + primeira posição
zero nos testes nativos).

Sucesso: stats (`g_last_stats`) refletem só o pedido atual; erro limpa e
zera sampler; falha deixa contexto PRONTO (memória já limpa no início do
próximo pedido) ou, se `llama_decode` falhar no meio, o próximo `generate`
limpa de novo — nenhum reaproveitamento de stats/resultado antigo.

## 6. Política de erros

- Falha em tokenize/decode: `set_error`, libera sampler, retorna null;
  `translating=false` na UI (já existe). Próximo pedido re-limpa.
- `llama_memory_clear` é void (não falha); se `llama_get_memory` for null,
  retorna erro "contexto sem memória" em vez de seguir com estado sujo.
- Sampler/history/RNG por pedido seguem config atual (seed 42 via
  `llama_sampler_init_dist(42)`); sem aceitar token duas vezes (sample já
  aceita).

## 7. Testes

- RED primeiro: `HyMt2RequestIsolationTest` (fake/seam JVM) verifica ordem
  `reset -> prefill -> decode` e que o 2º pedido não vê tokens do 1º.
  Falha antes do fix (sem reset exposto).
- Nativo real: harness/host que roda dois pedidos no mesmo contexto sem
  recarga e verifica `seq_pos_max==vazio` antes do novo prefill e primeira
  posição zero (mecanismos reais da revisão, não string).
- Gates: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`
  com exit codes guardados. Testes Android sozinhos NÃO verificam JNI.
- Regressão permanente: A->B->A mesmo contexto/modelo; longo->curto;
  A->A; falha no prompt/decode + novo pedido; limite de contexto + curto;
  load/backend switch + pedido; release em ordem segura.

## 8. Ativação/reversão diagnóstica

- Build local permitido; biblioteca diagnóstica com o fix + log de
  `seq_pos_max` antes/depois (opt-in, removível). Sem promover release nem
  pacote remoto para prova de campo.
- Instalação/ativação no aparelho só com aprovação específica (artefato +
  hash + escopo + reversão: preservar lib/pacote original e hash, não
  apagar dados/modelos). Sem autorização, entregar bloqueio do teste do
  app atualizado, não chamar app antigo de corrigido.
- Reversão: reinstalar lib original preservada; `COMPONENT_VERSION` e
  hashes registrados separadamente; nunca presumir que `assembleDebug`
  recompila nativos (AGENTS: build rápido não compila nativos por desenho).

## 9. Prova de runtime no alvo (pendente de aparelho)

Artefatos prontos (executar quando o CPH2747 voltar ao USB):

- `native-dependencies/harness/hymt2_reset_runtime_test.cpp` + binário
  `arm64-v8a/hymt2_reset_rt` (arm64; NEEDED libvulkan/liblog/libm/libdl/libc).
  Mesmo helper do produto (`hymt2_request_reset.h`) contra o contexto real:
  (a) `llama_memory_seq_pos_max/min` reais antes e depois do helper
      (esperado: valor do pedido A -> -1/-1);
  (b) pedido B após reset idêntico ao B de contexto fresco (sem herança);
  (c) controle negativo sem reset.
  Caminho CPU por desenho; modelo Q4_K_M do app em
  `/sdcard/Android/data/br.gov.sp.pcsp.launcher/files/hymt2_models/`.
- Comando: `adb -s <serial> shell /data/local/tmp/hymt2_reset_rt <modelo.gguf>`.
- Estado: não executado (o aparelho saiu da USB antes). A prova de campo
  já feita com a lib corrigida (longo->curto warm, saída idêntica ao fresco)
  cobre o efeito; este teste adiciona a leitura direta de `seq_pos`.
