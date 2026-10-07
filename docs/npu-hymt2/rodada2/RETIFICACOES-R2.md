# RETIFICACOES-R2.md — auditoria da Rodada 2 (pos-parecer do especialista)

Cada item: afirmacao original -> correcao com evidencia.

## A1. Q4_K_M produto: bench executado SIM; traducao e2e NAO
- EXECUTADO (05:52): llama-bench sintetico pp64/tg32 nos DOIS backends
  (trials d2_q4km_htp / d2_q4km_cpu; modelo lido DIRETO do app dir,
  hash c4bf1015...; HTP pp64=1165,7/tg32=26,2 | CPU pp64=271,8/tg32=37,7).
- NAO EXECUTADO: traducao end-to-end com o Q4_K_M (corpus real), nem
  contexto/config do app (ctx/FA/KV dtype do app nao reproduzidos no CLI).
- A frase do relatorio R2 secao H ("Q4_K_M do produto nao foi exercitado no
  HTP") estava ERRADA frente ao D.2 -> retificada: o bench foi; a e2e nao.
- RunIDs: d2_q4km_htp-055213.out / d2_q4km_cpu-055227.out (logs em
  C:/llama-npu/rodada2/logs/).

## A2. "Processo por uso" NAO e o contrato do SIG (confirmado no source)
- app/src/main/cpp/llama-jni/llama_jni.cpp: g_model/g_ctx ESTATICOS (L41-42),
  loadModel mantem e reusa (L432-435); unload libera (L467-473); cada request
  usa hymt2_begin_fresh_request (L516) + generate no MESMO g_ctx (L631).
- => O app NAO recarrega o modelo por request; o custo CLI de ~48 s (repack
  CPU Q4_0 upstream) NAO se aplica como baseline produtivo. Comparacao
  honesta: startup amortizado em sessao persistente; o ganho "processo por
  uso" vale apenas para execucoes curtassem sessao (nao o contrato atual).
- O app usa o fork proprio (app/src/main/cpp/llama) com backend de produto
  (Vulkan) distinto do CLI upstream; numericamente nao equacionaveis sem
  teste no app (nao realizado; fora do escopo autorizado).

## A3. "Doze" — rotulo corrigido
- Evidencia R2: frequencias 384-768 MHz em tela apagada (madrugada), CPU
  ~30x mais lenta; HTP imune; wakeup+stayon restaurou.
- NAO capturamos na janela: dumpsys deviceidle/light|deep states, battery
  stats, thermal, cpufreq time_in_state. Sem esses dados, o mecanismo exato
  (Doze leve? CPU throttling de tela? cgroup policy?) NAO esta confirmado.
- Rotular como: "degradacao observada com tela apagada/background (mecanismo
  especifico nao capturado)"; "carregando 100%" nao e prova de Doze.
- Acao: coletar dumpsys no device quando reconectado (com script pronto).

## A4. F2 ganho ~8% (=ESTIMADO, nao medido)
- O ganho ~8% (Np=1000) e PROJECAO do modelo linear com numeros de bench —
  NAO e medicao end-to-end do hibrido (nao medimos uma request hibrida real
  com timers completos). Export/import medidos em 15 posicoes (0,09/0,17 ms);
  NAO extrapolar linear para 1K/4K (inclui metadata fixa e o custo de
  preparacao do contexto destino). "~61 KB/pos" e o tamanho serializado por
  posicao OBSERVADO (918.472 B / 15); explicitar que inclui metadata fixa,
  NAO e uma constante exata de armazenamento — reportar como faixa.

## A5. Totais das traducoes: DERIVADOS (nao wall medido)
- Os "CPU total / HTP total" da tabela = aritmetica np/pp + ng/tg com np/ng
  APROXIMADOS (estimados por tokens do texto; nao sao n_p_eval/n_eval exatos
  do journal do llama).
- Os WALL times medidos (incl. load/repack/IO/sampler) constam nos trials
  (c3_*): CPU 53,9-85,4 s; HTP 7,3-32,8 s. Apresentar os DOIS lado a lado e
  rotular derivado vs medido.

## A6. Corpus da R2 e NOVO (nao os originais historicos)
- Os tamanhos 409/952/2335/4924 chars foram RECRIADOS na R2 (originais do
  historico nao localizados); NAO comparar com historico por tamanho.
- SHA-256 dos arquivos do corpus (novos):
- long-2335.txt: 0e86a9bebafbdd10b049af1372929501612003ed4052710e20b4ac273fa9fac1
- medium-952.txt: b49d92d0a2c6708fd92c785b0acb9718fc1a30cd7dcfd984bffc37d0e8691186
- short-409.txt: 961ccbfd3d160bb46f2937cdb3247e312261941ecd42b3ad5ae8d97ff0d240b1
- xlong-4924.txt: 2c09913c2b3d3b79319859fe2ef9f336abf42d1412e581acdefa9e5961a5913c
- Break-even: os valores (3,16 sintetico / ~0,56 com pp real) sao por-caso,
  com premissas explicitas (modelo linear; pp/tg constantes) e NAO um "ratio
  unico para quase toda traducao" — apresentar workloads/assumptions.

## A7. Tuning: "limite estrutural" e forte demais
- Correcao: entre as configuracoes TESTADAS, o default foi o melhor equilibrio.
  Outros kernels/overheads nao foram eliminados (nao testamos patch de kernel
  nem batching novo). tg32 vs tg64/tg128: comparacoes so no MESMO campo de
  bench (tg64 e3 vs tg64 e3; tg128 C.2 vs tg128 C.2) — nao cruzar.

## A8. Hash do G1 e MD5 (nao SHA-256) — e o igual e TOKEN STREAM, nao byte
- g1-stability.sh usa `md5sum` -> 16a61449c65ef78debef67fb0193f363 e MD5 de
  32 hex do TEXTO da resposta. NAO e SHA-256; NAO e byte-equal de tensores.
- Classificacao correta: "mesmo token stream/texto de saida entre backends"
  (12/12 runs) — evidencia forte de consistencia, mas nao "bitexact" no
  sentido numerico. Se necessario, recapturar com sha256sum + texto integral
  (script pronto para quando o device voltar).
