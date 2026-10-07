# BLOCO F — RESULTADOS (hibrido)

## F2 TEMPORAL: HTP->CPU via llama_state_seq (PROVADO, corretude OK)

### Protocolo executado (hybrid_bridge.cpp v4)
1. ctx HTP0: prefill de np-1 tokens (deixa o ULTIMO fora).
2. lima_state_seq_get_data -> 918472 bytes (15 posicoes; ~61 KB/pos).
3. ctx CPU: llama_state_seq_set_data -> pos_max=13 (KV importado).
4. Ponte: 1 forward do ultimo token do prompt (pos np-1 = X+1, regra
   "posicoes consecutivas" do llama.cpp) -> logits no CPU.
5. Sample greedy + 4 geracoes no CPU.
6. Comparacao com referencia tudo-CPU (mesmo processo, mesmos pesos).

### Resultado
- Controle CPU->CPU: streams identicos (serializacao validada).
- HTP->CPU: streams IDENTICOS a referencia: [185,46,111664,1654].
- export 0,09 ms / import 0,17 ms (0,9 MB) — copia de memoria ~10 GB/s.
- bridge (1 token + sample): 37 ms (sob pressao de memoria do periodo).

### Regras/limites descobertos
- llama.cpp exige posicoes consecutivas (Y = X + 1): o destino NAO pode
  re-decodificar o ultimo token; a ponte e o ULTIMO token do prompt.
- n_p_eval no destino = 1 (apenas a ponte) + decodes; prefill nao se repete.
- Custo memoria do state: ~61 KB/posicao (1 KB p/ 15 pos era 918 KB).
- Corretude comprovada por equivalencia de stream greedy.

### Veredito
TECNICAMENTE VIAVEL com corretude provada. Ganho para traducao tipica
(Np~Ng, decode-dominante) e modesto (~8% em Np=1000); para workloads
prefill-dominantes o ganho e relevante. NAO e "trocar backend em runtime"
generico — e um handoff por request com custo de state proporcional ao Np.

## F1 ESPACIAL (opfilter) — nao executado (F2 satisfatorio; registrar como pendente)
## F3 (mesmo contexto/custom scheduler) — nao necessario (F2 cobre o caso util)
