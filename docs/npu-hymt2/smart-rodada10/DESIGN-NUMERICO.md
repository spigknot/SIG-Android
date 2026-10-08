# DESIGN NUMERICO — criterio de tolerancia da ponte Smart (R10)

Objetivo: decidir quando a diferenca numerica entre origem (HTP) e destino
(GPU) apos o handoff pode ser classificada como NUMERICO_TOLERADO, sem
remover o gate estrito, e sem criterio "sob medida" para a 1a amostra.

NAO usar: porcentagem da margem sobre o logit absoluto (logits admitem
deslocamento aditivo; 0.0096/26.7 nao e' medida equivalente). NAO fixar
tau olhando apenas o caso observado (pos 264, margens 0.0096/0.0229).

## 1. REFERENCIA (baseline de controle, por BACKEND DESTINO)
Para cada configuracoes/corpus: recomputacao PURA no MESMO backend destino
(sem handoff) com o MESMO prefixo teacher-forced. A distribuicao de erros
B (destino puro vs si mesmo em reexecucoes, e origem-CPU vs destino-puro
nos MESMOS prefixos) define o ENVELOPE DE REFERENCIA. O criterio de
tolerancia e' calibrado POR ESTE envelope — nao pela primeira amostra
Smart.

## 2. COLETA (por divergencia e por prefixo)
- mesmo prefixo teacher-forced, mesmas posicoes consumidas;
- logits FINITOS em todos os passos examinados (FAIL em NaN/Inf);
- top-k (k=5..10) dos dois lados com IDs;
- margens top1-top2 de ambos; softmax/log-probs normalizadas relevantes;
- erro absoluto dos logits CENTRALIZADOS (subtracao da media por passo,
  eliminando deslocamento aditivo) e erro relativo com cuidado perto de
  zero; medida de divergencia de distribuicao (ex: max |p_d - p_s|);
- maximo e DISTRIBUICAO dos erros; numero de divergencias por execucao
  (nao somente a primeira posicao); posicoes antes/na primeira/ao final.
- prefixos SELECIONADOS (antes da divergencia, a divergencia, final) —
  nao dump de vocab; poucas execucoes.

## 3. CLASSIFICACAO
- NUMERICO_TOLERADO: toda divergencia de argmax ocorre com (a) top-k
  sobrepostos, (b) erro centralizado dentro do envelope do baseline para
  aquele backend, (c) margens/probabilidades dentro do envelope, (d)
  zero NaN/Inf, (e) nenhuma divergencia fora dos pontos ambiguos do
  envelope. Motivo + metricas persistidas por caso.
- ESTRITO (mantido): qualquer divergencia fora do envelope, import/
  posicao/stop invalidos, cap, NaN/Inf => FAIL/nao-aceito (o gate de
  codigo permanece; a tolerancia so' RE-ROTULA com motivo).
- Fixture negativa: perturbacao de estado/logits suficiente (ex: escala
  ou troca de posicao forjada no mutante) deve ser REJEITADA mesmo se o
  texto final continuar plausivel.

## 4. ORDEM DE EXECUCAO
1) baseline/controle no build atual (SIG v12) — calibra o envelope;
2) candidato B (quando buildar) — mesma coleta, mesmo envelope;
3) so' entao reclassificacao. O candidato NAO precisa resolver a
   numerica antes de compilar/medir prefill (paralelo permitido pela
   ordem).

## 5. IMPLEMENTACAO (probe)
- o probe ja coleta: top-5 (DIV_DET), margens, finite, pos, tok IDs.
- FALTA: softmax/log-probs normalizadas e erro centralizado por passo +
  envelope (script host sobre os JSONLs). Implementar como pos-processo
  host (nao inflar o device) na proxima rodada de coleta.
