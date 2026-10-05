================================================================================
RELATORIO CONSOLIDADO FINAL — Hy-MT2 / Vulkan / CPH2747 (30/09/2026)
VULKAN: USO EXPERIMENTAL TECNICAMENTE SUSTENTADO NO APARELHO TESTADO
================================================================================
Documento FINAL, separado dos relatos historicos (PROMPT-RELATORIO-RODADA*).
Nao e' publicacao nem aprovacao de produto. Nada commitado/publicado.
Revisao documental final (30/09) aplicada sem alterar produto: indice de
evidencias com cabecalho unico e contagem conferida por script; retirada
de "alinhado ao app" no bloco A; separacao entre menus (preferencia) e
LIMPAR (risco visual) no bloco D; limite explicito do mutex por chamada e
da procedencia fonte/binario; procedencia de R (matriz f64 com decoder
Python verificado por bytes) separada de Rq_real (rotina C executada);
reforco de que PT->PT nao e traducao interlingual e de que a qualidade
nao e declarada perfeita. Diagnostico amplo Vulkan ENCERRADO por ora.

INDICE DE ESTADOS: CCE / PARCIAL / PENDENTE / BLOQUEADO

A. ESCOPO, APARELHO, DRIVER, BIBLIOTECA, MODELO, HASHES, CONFIGURACOES
   CCE (identidade conferida a cada sessao; porta reconfirmada por round)
   - Aparelho: CPH2747 (OnePlus 15), Android 16. Serial explicito por run.
   - Driver: Vulkan "Adreno (TM) 840 (Qualcomm Technologies Inc. Adreno
     Vulkan Driver) | uma:1 | fp16:1 | bf16:0 | fp4:0 | warp size:64 |
     int dot:0 | matrix cores:none".
   - App instalado: br.gov.sp.pcsp.launcher versionName=1.505
     versionCode=68 (via dumpsys package).
   - Biblioteca nativa CARREGADA (via run-as sha256sum):
     no_backup/native_dependencies/9-arm64-v8a/lib/libsig_llama.so
     sha 31f6df15bf9b4fbdf073adf033eac55f353b4240e7894ec1d12469163516d2c2
     = pacote v9 publicado, byte a byte.
   - Modelo exercitado: Hy-MT2-1.8B-q4_k_m.gguf
     sha c4bf1015b01fcc97b1510f58eca78604eb45758470e83f317ffbfae29dbe4559
     (1.133.080.544 B). Q4_0 (1.076.850.528 B) aparece UMA vez como prova de
     caminho (r17) e NAO como caso controlado.
   - Config do produto (fonte lida, nao inferida): n_ctx 8192,
     n_batch 2048, n_ubatch default, threads 6 (preferencia do aparelho),
     FLASH ATTENTION DESLIGADO no caminho GPU (llama_jni.cpp:320-326),
     AUTO na CPU; sampler penalties(1.05)->top_k20->top_p0.6->temp0.7->
     dist42; template hy_* com U+FF5C; add_special=false; EOG 120020.
   - Config do harness (diagnostico numerico): n_ctx 4096, n_batch 512,
     threads 4/4, FA=AUTO, modelo Q4_K_M. Configuracao DISTINTA da do app
     (8192/2048/6/FA_OFF_GPU): a aceitacao funcional foi medida NO APP
     pela UI; o diagnostico numerico foi feito no harness. Nao sao a mesma
     configuracao e nao devem ser lidas como tal.

B. EXECUCAO E QUALIDADE POR CASO, IDIOMAS, MODALIDADE, COBERTURA
   CCE (execução) / PARCIAL (qualidade: 1 erro de modalidade; idiomas
   exercitados sao so pt->pt)
   Casos SINTETICOS (sem dados pessoais): curto, medio, longo, numeros,
   negacao, estrutura. Fontes em logs-rodada18/fontes (sha por texto).
   IDIOMAS: fonte e' PORTUGUES; idioma alvo registrado = Portugues. Os
   casos exercitam PT->PT (preservacao/normalizacao/parafrase), nao
   traducao interlingual. Nao se generaliza qualidade en->pt.
   VULKAN (Q4_K_M, 6 casos, todos CONCLUIDO):
     curto     COMPLETO, numeros/hora/unidade ok
     medio     COMPLETO, 6 frases, numeros ok
     longo     COMPLETO, cobertura integral (15/15 fatos, matriz r20)
     numeros   COMPLETO, CNPJ/datas/valores/unidades ok
     negacao   COMPLETO, negacao nao invertida, condicional preservada
     estrutura COMPLETO com 1 FALHA SEMANTICA item 1
   CPU (Q4_K_M, 6 casos, todos CONCLUIDO):
     mesma cobertura; item 1 preservado ("Verificar"); item 3 alterado
     ("Fechar"->"Preencher": acao diferente — ver D)
   ERRO DE MODALIDADE (estrutura, item 1): fonte "Conferir o lacre" (INSTRUCAO);
     Vulkan "Conferiram o selo" (ACAO CONCLUIDA) = falha semantica; CPU
     "Verificar o selo" (INSTRUCAO preservada). CAUSA NAO LOCALIZADA (nao e'
     atribuida a kernel/modelo/prompt/sampler — nenhum foi provado).
   COBERTURA do longo (retificada): o campo saida_final do caso.json do
     longo_CPU trazia a traducao do medio (captura errada do instrumento,
     sha igual). Refeito a partir de ui_final.xml: cobertura COMPLETA nos
     dois backends. "Embalagem rompida"->Vulkan "quebrada" = sinonimo com
     perda de especificidade (fato operacional preservado).

C. PEDIDOS SUCESSIVOS E ALTERNANCIA — LIMITES DE ACTIVITY/CONTEXTO
   CCE (execucao) / PARCIAL (limite de Activity nao fechado)
   - Reuso A->B->A: PID 29305 estavel nos tres pedidos, sem `am start` no
     ramo de preparo (testado), sem contaminacao de conteudo. PROVA: pedidos
     sucessivos no MESMO PROCESSO. NAO prova ausencia de recriacao da
     Activity (PID estavel e' necessario, nao suficiente; nao havia log de
     lifecycle para fechar). Contexto nativo pode recriar por pedido —
     implementacao real nao verificada.
   - Alternancia: Vulkan->CPU->Vulkan pela UI, sem tarefa ativa, com
     traducao apos cada troca e backend EFETIVO no log do app
     ("GPU Vulkan (Vulkan/Vulkan0)" / "CPU"); 3 CONCLUIDO; saidas
     identicas. Nao reexecutado em campanha ampla.
   - Nao cobertos: A->A consecutivo, nova sessao pelo fluxo normal.

D. CICLO DE VIDA / CONCORRENCIA: FONTE RASTREADA ATE O JNI — PARCIAL
   (rastreada no fonte; riscos deontar ainda nao TESTADOS no aparelho)
   Tabela caminho->recurso->thread->trava->comportamento->risco (path:line):
   - translate() [TextoActivity.kt:~185] captura parametros (source, model,
     backendVez, threads) ANTES do worker; constroi prompt; chama
     HyMt2Native.generate(...) [TextoActivity.kt:~340]. Parametros
     capturados sao imutaveis durante o worker (locais validos).
   - GUARDA DE REENTRADA: `if (translating) return` +
     `buttonTranslate.isEnabled=false` no inicio de translate(); reabilitado
     ao fim do worker. Segundo toque no botao Traduzir e' IGNORADO (nao cria
     tarefa concorrente). Fonte: leitura direta de translate().
   - MENUS backend/modelo/idioma/threads [showBackendMenu etc.]: alteram
     APENAS PREFERENCIA, capturada no proximo pedido; NAO invocam
     load/release no pedido ativo (comentario explicito no fonte: "O modelo
     precisa recarregar no backend novo na proxima traducao"). CLASSIFICACAO:
     configuracao do proximo pedido — NAO e' risco de recarga concorrente
     (nao ha recarga durante a inferencia). Os parametros sao capturados em
     variaveis locais antes do worker, logo sao imutaveis durante ele.
   - LIMPAR durante geracao (comportamento DISTINTO do acima):
     button_clear_input / clear_translation NAO consultam `translating` e
     limpam os campos direto; nao ha ID/geracao de tarefa. Um callback
     posterior PODE preencher a saida depois do LIMPAR. RISCO DE FONTE
     (visual, nao de recurso): sobrescrita aparente. NAO exercitado nesta
     rodada (nao acionei).
   - onDestroy -> Thread{ releaseModel() } -> JNI releaseModel
     [llama_jni.cpp:~376]: toma `g_mutex` e, se houver contexto/modelo,
     llama_free/llama_model_free. O release BLOQUEIA enquanto a chamada
     protegida estiver em curso.
   - generate [llama_jni.cpp:~392] toma `g_mutex` e o segura durante toda a
     inferencia (tokenize, sampler chain, llama_decode, sampler_sample);
     loadModel tambem o toma. As 9 funcoes JNI usam o mesmo mutex.
   - LIMITE DE AFIRMAÇÃO (nao extrapolar): o mutex por chamada MITIGA a
     liberacao DURANTE uma chamada protegida no fonte lido; isso NAO prova
     lifetime seguro em toda a sequencia — um lock por chamada nao da
     atomicidade a sequencia load->generate se outra Activity/instancia,
     ou um release, intercalar ENTRE chamadas. Tambem nao ha prova de
     equivalencia fonte<->binario instalado (ver PROCEDENCIA abaixo).
   - RISCOS RESIDUAIS DE FONTE (nao testados, nao alegados como bug):
     LIMPAR sem guarda e sem ID de tarefa; callback tardio apos LIMPAR;
     onDestroy deixa o worker vivo ate o release concluir. Nenhum crash
     ou race foi OBSERVADO (nenhum teste destrutivo acionado).
   PROCEDENCIA (LIMITE): fonte llama_jni.cpp
   sha 3090494224aef53d56893b16525d821f854efe5385a0fb6a202fe37239364935.
   A correspondencia dessa FONTE com o BINARIO v9 carregado
   (sha 31f6df15...) NAO foi provada por build-id/assinatura nesta
   rodada; presume-se que o APK instalado foi construido a partir do
   fonte atual (mesma revisao de trabalho), o que permanece como
   pressuposto, nao como verificacao.

E. CANCELAMENTO — AUSENTE; CRITERIO AMPLIADO NAO ATENDIDO — PARCIAL
   CANCELAMENTO EXPLICITO DURANTE GERACAO NAO DISPONIVEL na UI atual
   (fonte + UI: sem botao; "Cancelar" e' so rotulo de dialogo). Lacuna de
   produto, NAO regressao do Vulkan. Nao e' exigido para provar que Vulkan
   executa. Nao implementado (sem autorizacao). Se implementado: sinal de
   abort do runtime real + checagem por token; cancelamento cooperativo
   (prefill/carga podem nao interromper); UI cancelada != worker encerrado;
   nao liberar contexto/modelo em uso; ID de pedido contra callback
   tardio; guarda de onDestroy; testes permanentes cancelar->novo pedido,
   saida tardia, fechar Activity, trocar configuracao.

F. PRECISAO NUMERICA — CCE (com limites declarados)
   Referencias, com procedencia CORRETA por grandeza:
   - Rq_real: bytes Q8_K produzidos pela ROTINA C REAL do fork
     (verify_rq17.cpp -> quantize_row_q8_K) + o decoder Python do peso,
     multiplicados em f64 no PC. Artifact-backed.
   - R: matriz f64 (X f64 x W dequantizado pelo decoder Python,
     este confrontado com calculo independente feito A PARTIR DOS BYTES do
     GGUF). O decoder de W em C NAO foi executado: nao existe artefato que
     o prove. Portanto R e' referencia numerica com decoder Python
     verificado por bytes — NAO "referencia C executada".
   O Python foi transricao matematica; o que decide e' o confronto com R e
   Rq_real acima, nessa ordem de forca de evidencia.
   - CPU reproduz Rq_real (max 5.07e-08); Vulkan x R = 0.5634%;
     Vulkan com ggml_mul_mat_set_prec(..., GGML_PREC_F32) = 0.0210%
     (reduz ~27x no operador, processo limpo por variante).
   - Mecanismo: acumulador FP16 do MUL_MAT (fonte do seletor/gerador);
     trace do pipeline EFETIVO ausente (op_params[0] confirma o parametro
     solicitado, nao o pipeline executado).
   - Nao comprovado efeito no erro de modalidade nem em L[342]. Nao e'
     promessa global nem causa de L[342]. F32 NAO aplicado.
   - Anomalia multivariante do replay: INVALIDA, causa NAO determinada
     (new_tensor zerar op_params afasta uma hipotese especifica sem provar
     o resto). Protocolo vigente: processo limpo, uma variante/processo.

G. PROCEDENCIA DE LOGS/TENTATIVAS/METRICAS; INVALIDACOES; VACINA — CCE
   - 4,7 s / 12 tok / 44,4 tok/s = Q4_0 (r17), NAO do caso curto Q4_K_M.
     Procedencia real do Q4_K_M curto: Vulkan 7.4 s / 38 tok / 35.1 tok/s;
     CPU 5.4 s / 38 tok / 20.1 tok/s; longo Vulkan 13.1 s / 236 tok /
     32.2 tok/s; longo CPU SEM log contemporaneo (metrica indisponivel).
   - Diretorios com campo invalido: smoke_longo_CPU e smoke_medio_CPU
     (saida_final = traducao do outro caso; evididencia primaria = ui_final.xml).
     Os originais foram PRESERVADOS; o derivado corrigido e' logs-rodada20/.
   - VACINA DO INSTRUMENTO (aplicada e TESTADA, nao produto): CONCLUIDO
     exige log CRESCIDO da TAREFA com "pronta"/"Desempenho" + saida estavel
     2 leituras; status herdado ("Traducao pronta" antigo) + saida antiga
     NAO aprovam; saidas identicas passam se a tarefa for nova. Teste
     permanente testes_vacina21.py: 9/9, RC=0. Diff da vacina:
     +27/-3 (logs-rodada21/vacina_instrumento.diff). Bug real encontrado
     pelos testes na propriacura (acumulo de log_da_tarefa) e corrigido.
   - Suites do instrumento: testes_bateria18 31/31 RC=0;
     testes_ciclo19 10/10 RC=0; testes_vacina21 9/9 RC=0.

H. DECISAO — INVESTIGACAO AMPLA VULKAN ENCERRADA POR ORA
   EXECUCAO Vulkan Q4_K_M: SUSTENTADA na amostra (6/6 CONCLUIDO, backend
     efetivo por log, saidas completas).
   QUALIDADE: 5/6 Vulkan sem erro; 1 caso (estrutura item 1) com falha
     semantica de modalidade (ORIGEM NAO LOCALIZADA); 6/6 CPU sem falha
     de modalidade no item 1, mas com ALTERACAO DE ACAO no item 3
     ("Fechar"->"Preencher", acao diferente em lista de tarefas).
     Os casos sao PT->PT: exercitam preservacao/paráfrase, NAO traducao
     interlingual. NENHUM caso validado como traducao entre idiomas
     distintos e nenhuma qualidade perfeita esta declarada — sao erros
     observados, com origem nao localizada, sem atribuicao a kernel.
   ALTERNANCIA: SUSTENTADA (Vulkan->CPU->Vulkan, backend por log).
   PEDIDOS SUCESSIVOS: SUSTENTADOS no mesmo processo (limite de Activity
     nao fechado).
   CICLO DE VIDA/CONCORRENCIA: reentrada protegida; release bloqueante
     (use-after-free mitigado no fonte); LIMPAR/menus sem guarda = riscos
     de fonte NAO testados no aparelho.
   CANCELAMENTO: INDISPONIVEL (lacuna conhecida de produto; criterio
     ampliado nao atendido).
   DESEMPENHO: medido, amostras unicas, sem promessa (procedencia
     corrigida).
   PRECISAO: controle local favoravel; proposta NAO aplicada.
   REVISAO HUMANA DO TEXTO GERADO E' NECESSARIA antes de qualquer uso
     como resultado final (especialmente em listas/instrucoes).
   SEM GARANTIA de qualidade/robustez universal; sem generalizar a outros
     Android/modelos/FA (o app desliga FA no GPU por decisao anterior).

PROXIMA FRENTE RECOMENDADA (nenhuma iniciada automaticamente; todas
exigem decisao separada do usuario):
  RECOMENDACAO DE PRIORIDADE: VALIDACAO INTERLINGUAL CURTA. E' a lacuna
  funcional principal: toda a bateria exercitou PT->PT (preservacao/
  parafrase) e nenhuma traducao entre idiomas distintos foi medida. Sem
  ela, o tradutor nao pode ser anunciado como validado para o uso-alvo
  (traducao). Escopo sugerido: poucos casos EN->PT (e/ou a lingua de
  origem real de uso), CPU e Vulkan, mesmos criteria de qualidade
  (fatos, negacao, modalidade, cobertura, termino) e procedencia de log
  por tentativa.
  Em seguida, por ordem:
  1) LIFETIME/CONCORRENCIA — os riscos residuais de FONTE (LIMPAR sem
     guarda/ID; callback tardio apos LIMPAR; onDestroy) NAO foram
     exercitados; a liberacao DURANTE chamada protegida esta mitigada no
     fonte, sem prova de sequencia completa nem de equivalencia
     fonte/binario. Exercitar apenas o que for seguro, ou deixar como
     limite declarado.
  2) CANCELAMENTO SEGURO (produto, se desejado).
  3) QUALIDADE DE MODALIDADE/ACAO, com corpus representativo (o erro
     observado e' de modalidade/acao em lista de tarefas).
  4) F32 (melhoria numerica opcional; NAO e' tratamento do erro de
     modalidade nem solucao comprovada para L[342]).
  Nao retomar OpenCL/NPU sem decisao do usuario.

================================================================================
FIM DO RELATORIO CONSOLIDADO
================================================================================