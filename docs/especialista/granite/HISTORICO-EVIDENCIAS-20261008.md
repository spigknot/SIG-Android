# Granite STT — histórico e auditoria de evidências

Data de referência do handoff: 08/10/2026. Auditoria offline de leitura; os
resultados históricos abaixo não são medições novas no aparelho.
Revisão de alinhamento em 09/10/2026: prevalecem
[PLANO-GRANITE-20261009.md](PLANO-GRANITE-20261009.md) e
[ORDEM-G01-20261009.md](ORDEM-G01-20261009.md) para escopo e sequência operacional.

## Escopo e conclusão

Base auditada: `D:\SIG-granite-nar-lab-rebuild\nar-next-20260914-0950`;
artefatos referenciados também foram conferidos no diretório pai
`D:\SIG-granite-nar-lab-rebuild`. Não houve ADB, build, download, publicação,
alteração de históricos, acesso a E: ou leitura de arquivos de áudio pessoal.
Nenhuma transcrição pessoal é reproduzida neste documento.

A documentação final contém avanços reais e algumas correções ainda incorretas.
O achado principal é de interpretação da memória: `end.memory` é uma amostra
**após `release()`**, enquanto `inference.memory_after` mede o fim da inferência
antes dessa liberação. O extrator histórico chamou a primeira de pós-inferência
antes do release e ignorou a segunda. A economia atribuída ao N1 muda de sinal
quando se usa o momento correto. A primeira tarefa recomendada é corrigir o
extrator e regenerar quadros a partir dos mesmos runs, antes de novos testes.

## Fontes e precedência

Foram lidos `handoff/entrega-perfis-reais/RELATORIO-FINAL-20260925.md`,
`APENDICE-DADOS.md`, `ERRATA-ROTAS-CONTRATOS-20260916.md`, os marcos 01–11,
`memoria-publicos.md`, os scripts geradores e os JSON/streams associados.

Precedência para esta auditoria: bytes e eventos correlacionados do run,
`resultado.json`/`config.json`, avaliador congelado e referências versionadas,
quadros derivados, relatório narrativo. Um `valido=true` anterior às travas de
rota não comprova que a rota pedida foi executada. A existência de um relatório
também não comprova que seu gerador extraiu o campo certo.

Recomputação realizada em memória, com o Python já existente no laboratório e
`-B`, sem gravar intermediários nem regenerar os arquivos históricos. Foram
importadas apenas funções puras de `quality/analisa_metricas.py` e
`quality/qualidade.py`; os comandos operacionais dos drivers não foram executados.

## Cronologia das conclusões substituídas

| Período/fonte | Afirmação anterior | Evidência e conclusão que prevalecem |
|---|---|---|
| 15/09, avaliador e marco 01 | Delta escrito com sinal invertido; repetição 2×1 podia favorecer candidato | `gera_gate_ref.py` e `delta_pesos_iguais` fixam candidato menos referência e média de erros por áudio/perfil antes do denominador único. Recomputação dos JSONL reproduziu todos os deltas de 24 pares abaixo. |
| 15–16/09, marco 01–02/N2 | Frontend lento atribuído a mmap; encoder N2 de aproximadamente 1,6 s | A errata identifica 1,6 s como abertura de sessão, não inferência. O piloto era do pacote REF, apesar de `precisao=u8` declarativo. O marco 07 separa leitura, parse e mmap: Regex do vocabulário dominava o custo. |
| 15–16/09, CTC antigo | Comparação CTC CPU×HTP com 3/4 textos divergentes | `ctc-htp` CPU executou pipeline full; HTP executou CTC. A errata retira a comparação. Estágios e travamento HTP continuam observações, sem equivalência de rota. |
| 16/09, marco 03 | Dez ciclos ArgMax/token_ids | O run `ciclos-ciclos-a-453f0f00` era logits. A errata o preserva como controle. Dez ciclos reais token_ids aparecem somente em `argmax-ciclos-reais-3`, em 25/09. |
| 16/09, pessoais | Treze pares, delta −1,08/−0,60 pp | A errata/final registram 14 pares, −0,88/−0,49 pp e referências não verificadas. O marco 11 ainda conserva −1,08/−0,60 junto de “14 pessoais”. Esses números pessoais não foram reavaliados nesta auditoria e não devem aprovar perfil. |
| 25/09, marco 07 | Mmap dos embeddings seria o gargalo | Instrumentação registra mmap 0–3 ms e leitura 15–87 ms; parse Regex 14,705–21,327 s. Parser manual reduz parse a 62–217 ms e carga full para cerca de 3,1–3,3 s. Isso melhora carga, não prova melhoria da inferência quente. |
| 25/09, marco 06 novo | CTC NPU pareceria lento em entradas pequenas por warmup | Streams mostram abertura t400 na carga e t200 na inferência para 175 frames. Repetição conserva o efeito. É recriação de sessão por bucket, não uma amostra de inferência pura. |
| 25/09, marco 09–10 | `EXTENDED_OPT` poderia remover preparo repetido | ABBA tem 16/16 runs válidos; variação de −5,77% a +2,76%, com diferença de texto em dois áudios. Intervenção reprovada; não sustenta expansão ou promoção. |
| 25/09, final/apêndice/memória | Apenas três amostras existem; memória por ciclo não é mensurável | Streams contêm `memory_before` e `memory_after` por inferência. Dez ciclos reais têm 20 amostras de fronteira mais start/load/end. A ressalva do gerador final está errada. |
| 25/09, tabela “pareada” de memória | `end.memory` mede pós-inferência antes do release | Código e streams provam que `end` ocorre depois de `release()`. Os 72 valores de seis perfis conferem com o bruto, mas medem outro momento. Recomputação corrigida de oito perfis usa 96 runs públicos e aparece abaixo. |

## Qualidade que foi corroborada

Norma **v2**, delta = candidato menos referência, em pontos percentuais absolutos.
A chave de pareamento é SHA-256 do áudio registrado, hash da referência
normalizada e versão da norma; nome de arquivo é apenas rótulo. Hash da referência
é calculado pelo avaliador, não necessariamente armazenado como campo separado.
Não foram recalculados hashes de áudios pessoais.

Fontes: `quality/metrics-holdout-ptbr-normv2-sha.jsonl` (75 EQ + 25 N1),
`metrics-ref-float-sha.jsonl` (24 REF) e `metrics-q4-sha.jsonl` (24 Q4).
Os 148 registros usam v2. Foram recompostos os pares pelas funções congeladas;
24 pares, zero registros sem hash usados no gate, zero pares excluídos por null.
Denominador pareado: **255 palavras e 1.242 caracteres de referência**.

| Comparação | ΔWER pp | ΔCER pp | Pares |
|---|---:|---:|---:|
| EQ−REF | +5,490196 | +1,610306 | 24 |
| N1−REF | +3,529412 | +1,529791 | 24 |
| N1−EQ | −1,960784 | −0,080515 | 24 |
| Q4−EQ | −0,784314 | −0,161031 | 24 |
| Q4−REF | +4,705882 | +1,449275 | 24 |

Esses valores reproduzem `gate-vs-ref.json`. EQ/N1/Q4 não passam o limite de
+1 pp contra REF nesse conjunto. Q4 próximo de EQ é uma conclusão amostral;
não constitui prova geral de equivalência nem autorização de produção.
No conjunto pareado, erros médios por áudio somados dão REF 26 palavras/64
caracteres, EQ 40/84 e N1 35/83, confirmando os números do plano principal.
O holdout já orientou decisões; não é confirmação independente de um candidato
escolhido com base nesses mesmos resultados.

Ablação pública também foi recomposta com os resultados brutos, referências do
holdout congelado e `qualidade.avalia(..., normalizacao="v2")`. Para os 12 da
triagem, o denominador é **125 palavras/587 caracteres**. Para H8×R completo,
foram combinados apenas runs públicos de `ablacao-h8` e `ablacao-r-pers`:
**24 pares, 255 palavras/1.230 caracteres**. O denominador CER difere daquele
dos JSONL de gate; preserve cada referência versionada e não misture as baterias.

| Comparação | Conjunto | ΔWER/ΔCER pp | Corroboração |
|---|---|---|---|
| H8−R | triagem pública 12 | 0,000000 / 0,000000 | 48 linhas de `metrics-ablacao.jsonl` e respectivos brutos |
| H8−R | holdout público 24 | 0,000000 / 0,000000 | Recomputação dos 24 H8 e 24 R públicos |
| E8−R | triagem pública 12 | +8,800000 / +2,044293 | Mesmo conjunto e referência |
| EF−R | triagem pública 12 | +8,800000 / +2,044293 | Mesmo conjunto e referência |
| H4−R | triagem pública 12 | +1,600000 / +0,340716 | Recomputação dos 12 H4 válidos brutos |

`metrics-ablacao-h4.json` está incompleto: contém 59 registros, com apenas 11 H4.
Se usado diretamente, retorna 11 pares e +1,754386/+0,373832 pp. Os brutos têm
12 H4 válidos e uma tentativa inválida; a recomputação dos 12 explica e reproduz
o +1,60/+0,34 do relatório. Deve-se corrigir o derivado, sem repetir inferências.

O resultado H8 sustenta o ganho do editor menor no escopo medido; a perda de U8
se concentra no encoder nessa ablação. Latências REF/R/H8 variam muito entre
baterias e não devem virar promessa universal de desempenho.
Além dos deltas iguais a zero, o texto bruto H8×R foi confrontado nos 24 pares
públicos e coincide em 24/24.

## Memória: a correção mais importante

`memoria-publicos.json` é **JSONL apesar da extensão `.json`**. Os seis perfis
centrais contêm 72 runs do mesmo conjunto público de 12 áudios; todos os 72
valores de carga/end foram confrontados com seus streams e coincidem. Incluindo
EQ e N1 no mesmo conjunto, há 96 runs: todos apresentam uma inferência medida
com `memory_before` e `memory_after`.

Isso é um quadro **legado auditado**, não 96 provas completas da identidade do
runtime. Uma verificação adicional encontrou em 96/96 streams um único run_id
igual ao `resultado.json` e um único PID app do prefixo logcat. Nenhum desses
96 resultados registra `stream_sha256` histórico ou `session_id`; os JSON dos
eventos também não registram pid. O hash atual do input permite reproduzir a
extração, mas não cria retrospectivamente identidade de APK/modelo/bibliotecas.

Seguir a classificação da G01 por dimensão: PID do logcat correlacionado ao
start é evidência válida mesmo sem pid no JSON. Usar
`legacy_partial_identity` quando a captura não permitir essa correlação, e
registrar null/razão para campos históricos ausentes. Não converter ausência de
session_id em contaminação automática nem afirmar HTP integral por um enum.
Manter quadro legado e quadro estrito separados caso as travas reduzam o conjunto;
colisão real de run/PID exige exclusão/erro registrado, nunca escolha silenciosa.

O defeito não é cópia incorreta do número: é o campo/momento selecionado.
`memoria_publicos.py` restringe a extração a `memory` em start/load/end;
`memoria_tabela.py` chama end de “pós-inferência antes do release”.
`gera_apendice.py` afirma ausência de memória por ciclo porque também não lê
as propriedades `memory_before`/`memory_after` das inferências.

Corroboração no código corrente:
`D:\Projetos\SIG\app\src\debug\java\br\gov\sp\pcsp\launcher\GraniteNarSmokeTestActivity.kt`
libera o engine antes de emitir end (linhas 149–160) e registra fronteiras da
inferência (linhas 200–201). Isso também aparece nos históricos:

| Run público e momento | Linha do stream | Timestamp armazenado | PSS KiB | Heap nativo alocado, bytes |
|---|---:|---|---:|---:|
| `eq-10556936069191551163-f430d9ec`, inference.memory_after | 34 | 09-15 04:36:13.920 | 5.042.404 | 5.285.447.040 |
| Mesmo run, end.memory; release_ms=67 | 35 | 09-15 04:36:14.036 | 4.132.020 | 20.888.288 |
| `ctc-ctc-1651-ce62876c`, inference.memory_after | 18 | 09-25 03:29:47.822 | 2.149.844 | 1.724.745.176 |
| Mesmo run, end.memory; release_ms=61 | 19 | 09-25 03:29:47.912 | 1.398.328 | 518.584.360 |

Streams relativos: `ref-float/runs/10556936069191551163/eq/eq-10556936069191551163-f430d9ec/stream.log`
e `ctc-only-cpu-3/runs/ctc-1651/ctc/ctc-ctc-1651-ce62876c/stream.log`.
Os horários acima reproduzem o log; não foi inferido fuso a partir deles.

Fórmula corrigida: para cada perfil/áudio, mediana das amostras da **inferência
medida**; depois mediana dos mesmos 12 áudios. Delta pareado = mediana das
diferenças por áudio contra REF. PSS KiB/1024 = **MiB**, não MB. O pico observado
inclui start/load, before/after de cada inferência e end; continua sendo máximo
de amostras, não medição contínua do pico.

| Perfil | PSS carga MiB, quadro histórico | PSS end MiB, após release | PSS after MiB, corrigido | Δ after vs REF MiB, pareado |
|---|---:|---:|---:|---:|
| REF | 3.248 | 4.017 | **4.895** | 0 |
| R | 3.517 | 4.056 | **4.915** | +35 |
| H8 | 1.848 | 2.483 | **3.349** | −1.535 |
| E8 | 1.694 | 3.105 | **4.022** | −873 |
| EF | 3.394 | 4.662 | **5.548** | +662 |
| Q4 | 1.143 | 2.365 | **3.272** | −1.639 |
| EQ | Não selecionada no quadro antigo | 3.118 | **3.989** | −896 |
| N1 | Não selecionada no quadro antigo | 2.690 | **5.894** | **+997** |

O pico amostrado mediano coincide com after nesses 96 runs. As economias de H8
e Q4 continuam fortes. Para N1, o quadro antigo dizia −1.313 MiB pelo end;
o momento after registra **+997 MiB** contra REF. A conclusão de economia de
memória durante a inferência N1 não sobrevive.

No CTC CPU, after está em **2.026–2.102 MiB**, enquanto end fica em
1.292–1.368 MiB. No braço NPU, after é 3.711–4.010 MiB. A frase CTC “aproximadamente
1,3 GB pós-inferência” também precisa trocar campo e unidade.

Não há prova de ausência de vazamento a partir disso. PSS, heap alocado e
liberação observada respondem a perguntas diferentes; PSS pode conservar páginas
residentes depois que os objetos/sessões já foram liberados.

## ArgMax, carga, CTC e profiling

### ArgMax real

`argmax-ciclos-reais-3/runs/ciclos-argmax/ciclos/ciclos-ciclos-argmax-1fe3c90f`
tem rota full, contrato token_ids e dez inferências medidas válidas, índices
1–10; texto com hash `79cc62d9dad9` nas dez. Tempos em ms:
7688, 6969, 5712, 8280, 7577, 8464, 6428, 6420, 7399, 7962;
mediana **7.488 ms**. Carga **21.362 ms** no evento load, antes da otimização.

`memory_after.total_pss_kb` existe em todas as dez medidas, linhas
42/74/106/138/170/202/234/266/298/330. MiB arredondados:
3640, 4002, 4110, 4006, 4009, 4008, 4013, 3925, 4033, 4089;
mediana **4.009 MiB**, faixa 3.640–4.110 MiB. O marco 08 está correto ao mostrar
essas amostras. O final/apêndice está incorreto ao dizer que não existem.

Há 23 amostras de fronteira: start/load/end + 10 before + 10 after. Os dez
resultados estáveis não provam “zero vazamento”. O protocolo declara recarga
entre medidas; mesma Activity/processo não equivale a mesma sessão quente.

A inclusão desses ciclos no processamento público da G01 exige prova da origem
ou uma extração redigida sem conteúdo pessoal, conforme a ordem. As configs de
`argmax-ciclos-reais-3`, `vocab-split-medida` e `vocab-parse-depois` apontam para
`/sdcard/Download/pt-br-1651.wav`. O manifesto local
`D:\SIG-granite-nar-lab-rebuild\calibration\corpus-manifest.jsonl`, linha 7,
registra **google/fleurs, pt_br, validation, id 1651**, arquivo
`pt_br_1651.wav`, 7,08 s, 16 kHz, 226.604 bytes e SHA-256
`745ab759cc33a03b153ab0fae8086bd48fed8e3621edbc27aa577b4faaf158f2`.
O manifesto tem SHA-256
`e8f0c6d19bff8b18382f1c7a0a7a426c2d7a6b859e2420fd453c84f199f1e348`.

Essa origem pública é compatível com o nome do run, mas a auditoria não encontrou
uma ponte explícita de renomeio underscore→hífen nem hash do arquivo histórico
no aparelho. Nome semelhante e duração não provam identidade de bytes. Registrar
essa lacuna; não incluir automaticamente os streams de ciclos/vocab ou os casos
CTC pessoais no manifesto público integral. A alternativa já autorizada pela
G01 é consumir somente eventos redigidos sem conteúdo pessoal, mantendo
proveniência/limitação explícitas e sem escutar ou re-hashear áudio pessoal.

### Tempo de carga

Em `vocab-split-medida`, o evento load registra 20.845 ms. Em
`vocab-parse-depois`, registra 3.285 ms; o quadro 3.149–3.269 ms do marco 07 usa
o cronômetro interno do engine nos logs, não o mesmo intervalo externo.
Ambos sustentam redução de carga para cerca de 3,3 s. O próximo extrator deve
preservar campo/origem de cada intervalo em vez de tratá-los como idênticos.
O vocab rápido não explica os ganhos de inferência do encoder.

### CTC corrigido, mas ainda sem mediana quente

Brutos `ctc-only-cpu-3`, `ctc-only-npu-3` e repetição `ctc-only-npu-4`:
rota observada ctc, contrato none, único estágio `(ctc) encoder`;
`warmup_runs=0`, `measured_runs=1`. Não existe ainda a bateria pedida de uma
carga, um warmup e três inferências medidas na mesma sessão.

| Identificador do áudio | CPU load/inference elapsed ms | NPU load/inference elapsed ms |
|---|---:|---:|
| ctc-nar-curto, 175 frames | 544 / 1.837 | 18.480 / 17.070 |
| ctc-p2-175, 175 frames | 506 / 2.135 | 22.134 / 18.691 |
| ctc-p3, 300 frames | 492 / 2.689 | 20.496 / 1.535 |
| ctc-1651, 354 frames | 494 / 2.710 | 18.690 / 1.612 |

As pequenas diferenças do marco 06 são explicadas pelo uso de stage_ms e
cronômetro interno para inferência/carga; o apêndice usa elapsed_ms dos eventos.
O braço 175 frames inclui criação de sessão t200 durante a inferência, após
carga t400. A repetição p2 registra 19.311/18.021 ms. A indicação conservadora
de manter CTC em CPU permanece; não há prova estrita de atribuição integral ao
HTP nem de ausência de fallback. O contrato pedindo NPU não substitui essa prova.

### Trace e intervenção

`marcoC-profiling/ortprof-t200.json` e
`marcoC-profiling-t400/ortprof-t400.json` existem, com 2.977 eventos cada. Os
2.973 eventos Node em cada trace indicam CPUExecutionProvider. Cada trace
registra **um** model_run: 1.760,088 ms e 2.215,639 ms; inicialização de sessão
404,758 ms e 358,732 ms. Out_bpe/Gemm custa 237,342/242,198 ms; desquantização
do peso 201,576/170,142 ms. Esses nós aparecem uma vez por trace/run.

A fixação narrativa diz um warmup + um medido, mas os start dos dois runs
registram **warmup_runs=0** e **measured_runs=1**, com apenas um evento inference.
Também declaram `precisao_pacote=u8`. O nome “fp16.onnx” do arquivo U8 não o
transforma em REF fp16; esse trace mede CTC CPU com o pacote quantizado.
Portanto seu gargalo não deve ser automaticamente atribuído ao H8/REF lento.

ABBA recomposto dos 16 resultados por áudio e braço, todos válidos:

| Áudio | BASIC encoder ms, mediana de 2 | EXTENDED encoder ms, mediana de 2 | Ganho % | Textos iguais entre braços |
|---|---:|---:|---:|---|
| ctc-1651 | 1.753,5 | 1.734 | +1,112 | Sim |
| ctc-p3 | 1.739 | 1.691 | +2,760 | Sim |
| ctc-p2-175 | 1.370 | 1.399 | −2,117 | Não |
| ctc-nar-curto | 1.359,5 | 1.438 | −5,774 | Não |

Isso reproduz o marco 10 e sustenta rejeitar a intervenção. O script
`abba_resultado.py` precisa revisão antes de reuso: deriva a chave do nome do
run preservando prefixos `ctc-`/`ctcext-`, separando braços que deveriam parear.
Nesta auditoria, o pareamento usou o diretório do áudio sem sufixo A1/A2/B1/B2.

## Inventário reutilizável e limitações

| Item | Disponibilidade concretamente conferida | Limitação |
|---|---|---|
| `inventory/artifacts.json` | 36/36 caminhos existem no diretório pai e têm tamanho registrado; inventário soma 9.644.593.571 bytes | Os 36 grandes pesos não foram re-hasheados nesta rodada; disponibilidade/tamanho não comprova atualidade de todos os hashes. |
| Encoders REF e U8 | `pacote-r` e `pacote-u8` presentes. Hashes dos grafos t200/t400 recalculados: REF `b5436665…`/`3c6f85fa…`; U8 `f7752935…`/`949cc2b1…` | Não confiar na palavra fp16 do nome do arquivo; vincular pacote, grafo e pesos externos. |
| Editores existentes | fp16 3.263.500.288 B; int8b 1.657.409.536 B; int4b 841.617.408 B, conforme inventário/tamanho atual | −49,2%/−74,2% dizem respeito ao editor, não necessariamente ao download total. |
| Encoder externo | REF 1.085.993.664 B; U8 1.178.045.968 B | U8 é +8,5% maior em disco; precisão nominal não prevê tamanho. |
| Derivado ArgMax | `derivados/llm-int8b-argmax`: grafo 2.225.978 B, dados 1.657.409.536 B e manifest presentes | Precisa revalidar hashes/contrato antes de usar em outro runtime. |
| Corpus público | FLEURS pt_br/test: holdout congelado de 75 entradas, índice/TSV, tar de 621.291.324 B e 994 WAV extraídos disponíveis | Esta auditoria usou metadados/resultados; não fez download nem nova escuta. Conjunto comum de 12 é triagem enriquecida, não amostra neutra da população. |
| Corpus pessoal | Diretórios/metadados existem; referências historicamente não verificadas | Não lidos para aprovar qualidade, não reproduzidos, não enviados. A verificação humana continua pendente. |
| Avaliador | `qualidade.py`, `analisa_metricas.py`, `normalizador.py`, testes de regressão e congelamento presentes | Não mudar fórmula/norma para acomodar candidato. |
| Instrumentação/orquestração | `orquestrador.py`, `orquestrador_marco_a_teste.py`, `teste_lock_focal.py`, `ctc_tabela.py`, `analisa_trace.py`, `abba_marcoC.sh` presentes | Drivers têm efeitos operacionais; não foram executados. Corrigir os extratores antes de reusar seus quadros. |
| Memória e apêndice | `memoria_publicos.py`, `memoria_tabela.py`, `gera_apendice.py`, JSONL/Markdown existentes | Campo pós-inferência e unidades incorretos; gerador do apêndice mantém seções 1–4b antigas e não revalida tudo apesar da introdução abrangente. |
| Gate Android histórico | Relatório declara 500/0/0 no HEAD 0036f14; logs antigos locais mostram builds verdes | Nenhum gate novo executado e o gate final de 500 não foi comprovado nesta auditoria por resultados XML daquele HEAD. Não transferir o selo ao checkout corrente. |

Hashes SHA-256 dos extratores/trace auditados, para identificar esta versão:

| Arquivo | SHA-256 |
|---|---|
| memoria_publicos.py | d3c4f596070d6f20f2e4426505b60dac0381890ed748c293950551be5ad9b0b4 |
| memoria_tabela.py | 9c6176d79c2a480efc03a018b7f55c7652b5ec503fbe3760ceee76e5d8099d8d |
| gera_apendice.py | 3952a56ad79fa497b2a2a71333ab356408bd2c4193c70ff4ebed9aa0a24f4dee |
| abba_resultado.py | 7067fe52608a81e47a9b1cd4269e132bde9fa056af09964ebe5558787ebcba2c |
| ortprof-t200.json | 49bedf3339ba00a50be3d95be311394025b99665a77a0fa25cec8b7a733bf0d1 |
| ortprof-t400.json | b8fe1c402eb40691ebc616119dc726fe129c8d134d79e7e6f796056d0b99d211 |

## Primeira tarefa pequena: G01, extrator offline

Escopo concreto fixado na [ORDEM-G01-20261009.md](ORDEM-G01-20261009.md): criar
`tools/granite/nar/audit_benchmark_events.py`, testes focais e procedimento de
reprodução no checkout próprio do executor. Os três extratores históricos são
fontes para inspeção, não arquivos a corrigir no lugar. Usar os mesmos
`resultado.json`, `config.json`, JSONL de métricas e `stream.log`; regerar apenas
quadros de memória/tempo afetados. Não alterar originais, chamar drivers,
repetir runs ou recalcular qualidade nesta ordem.

Interface proposta para a implementação, ainda inexistente:
`--lab-root <raiz> --input-manifest <manifesto-congelado> --output-dir <destino-novo> --public-only --read-only`.
A descoberta restrita usa `--create-input-manifest <arquivo-novo>`; a extração
final só aceita o manifesto congelado. Inputs históricos permanecem somente
leitura e o output precisa resolver fora do lab, em tentativa nova.
Saídas mínimas: manifestos de entrada/entrega, ledger de tentativas/exclusões,
eventos, memória/tempo por evento, resumo público, quadros Markdown, errata,
testes, reprodução e patch. Cada linha deve conter run_id, perfil,
SHA do áudio registrado, SHA do stream, número da linha, evento, kind/index,
timestamp bruto e elapsed_realtime_ms, propriedade exata de memória, valor KiB
e MiB, momento de release e origem exata do intervalo de tempo.

Critérios de aceitação:

1. Recuperar as 96 inferências dos 12 públicos×8 perfis e suas amostras
   before/after no quadro legado auditado; validar correlação de run/PID,
   hashes dos inputs e contagens. Entregar classificação de identidade e quadro
   estrito separado se houver redução, sem inventar campos históricos ausentes.
2. Publicar separadamente start, load, before, after e end após release. Não
   misturar after/end, KB/KiB, MB/MiB, `engine_total_ms`, `elapsed_ms`, soma de
   stage_ms e parede do subprocesso.
3. Reproduzir as medianas/deltas corrigidos deste documento, incluindo N1
   after +997 MiB; discrepância deve gerar falha explícita, não descarte silencioso.
4. Recuperar as dez fronteiras ArgMax, preservando índices; retirar “não existe
   memória por ciclo” e “zero vazamento” dos quadros derivados. Incluir esses
   streams somente com origem pública demonstrada ou eventos redigidos; registrar
   recargas e não chamá-los de inferência quente na mesma sessão.
5. Registrar pico apenas como maior amostra observada e reconstituir tentativas
   inválidas como parte do denominador operacional, sem apagá-las por retries.
6. Separar criação t400→t200 dos intervalos CTC de 175 frames e distinguir
   load.elapsed_ms dos cronômetros internos antes/depois do parser. Não corrigir
   engine, bucket ou parser Kotlin nesta ordem.
7. Usar fixtures de texto/JSON já redigidas para testes offline de before/after,
   release, unidades, correlação e destinos permitidos. Demonstrar determinismo
   dos agregados em dois destinos novos e inputs históricos inalterados. Nenhuma
   credencial ou áudio pessoal no teste.

O derivado incompleto H4, o pareamento ABBA inteiro e qualquer qualidade pessoal
ficam como pendências documentadas; não são critérios de implementação de G01.

## Pendências depois de G01

G02 é o port mínimo do parser manual de vocabulário com paridade integral e gates
na base atual, depois da revisão da G01. G03 fecha contratos ArgMax, sessão e
bucket/reuso **antes** de novo HTP: sessão identificada,
uma carga no bucket correto, um warmup e três medidas na mesma sessão, sem
recarga durante a medição, com contagens pedidas/observadas e fingerprint do APK.
Se houver sessão t400 seguida de t200, reportar criação como criação, não como
inferência quente. Atribuição HTP estrita e fallback exigem prova separada.
G04 escolhe o primeiro piloto de hardware com inventário e versões efetivamente
carregáveis, conforme o plano principal.

Continuam pendentes: derivado de qualidade H4 completo e sua memória no conjunto
comum; correção focal do pareamento ABBA; referências
pessoais verificadas; diagnóstico das caudas CTC; piloto cirúrgico out_bpe somente
com decisão e artefato isolado; confirmação de qual pacote será candidato de
produção. Os experimentos de profiling U8 não resolvem a latência do encoder
REF/H8. Nenhum resultado deste documento autoriza push, assinatura, publicação,
troca de perfil de produção ou remoção de artefatos.
