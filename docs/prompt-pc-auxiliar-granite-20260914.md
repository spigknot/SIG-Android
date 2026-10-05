# Prompt ao PC auxiliar — Granite NAR: integridade, CPU e NPU

**Atualização do usuário:** está autorizada a geração adicional de artefatos quantizados e derivados quando necessária. A seção 10 define essa autorização e prevalece sobre os limites anteriores de quantidade de candidatos ou de exportações; os gates de qualidade, compatibilidade e publicação continuam obrigatórios.

Execute este plano a partir do relatório de 14/09/2026. Ele substitui as ordens anteriores nos pontos conflitantes. Entregue código revisável, evidências e candidatos experimentais; não publique APK, não faça push nem promova pacotes de produção. Use o disco saudável e o laboratório reconstruído; não acesse E:. Leia AGENTS.md e MODULE-MAP.md da sua cópia antes de editar. Preserve alterações de outras tarefas. Registre revisão Git, ORT, QAIRT, aparelho, firmware e hashes dos modelos usados. Verifique o estado atual: nomes e caminhos abaixo são referências, não prova de que sua cópia esteja sincronizada.

## Decisões D1–D8

- D1: recomendo restaurar o manifesto de produção no mesmo endereço, somente se a cópia tiver proveniência e hashes compatíveis com os objetos publicados. Nesta tarefa prepare e verifique a restauração; retenha a sobrescrita da chave de produção para aprovação explícita do usuário. HTTP 500 em um objeto prova indisponibilidade observada, não corrupção física do armazenamento. Corrija também o comportamento do aplicativo que aceita downloads sem hashes; restaurar o objeto sozinho não resolve essa falha de projeto.
- D2: U8 CPU + LLM int8b-blk128 é o candidato prioritário. Não está aprovado para produção com 2 repetições do mesmo áudio. Registre a precisão real do projector em cada configuração. O ganho de 7,2× inclui mudanças de modelo/precisão e condições diferentes; é referência histórica, não ensaio controlado atribuído exclusivamente à quantização.
- D3/D5: use igualdade normalizada no smoke, WER/CER com referência humana no corpus e diferenças brutas preservadas. Regras abaixo. Um ponto final isolado não reprova; “aprenderender” reprova o smoke lexical. Nada de pós-processamento para esconder a repetição.
- D4: sim, começar por seis áudios, mas tratá-los como triagem. Avaliação maior só após essa triagem e as correções prioritárias.
- D6: congelar LLM na NPU nesta rodada. A incompatibilidade de MatMulNBits está demonstrada para o runtime testado, não para todo QNN futuro. Não gerar outro LLM QDQ nem repetir AWQ/SmoothQuant/GPTQ agora.
- D7: preparar inventário de limpeza e, se necessário, quarentena recuperável no mesmo disco saudável. Não apagar definitivamente pastas nem o int4b do telefone nesta tarefa; conservar hashes e evidências até terminar os controles. Só mover um alvo após verificar caminho absoluto, ownership e ausência de processo que o use.
- D8: continuar NPU com escopo limitado: localização da divergência e cache de contexto mode=2. Somente depois considerar ArgMax no grafo. Suspender varreduras livres de opções, compartilhamento de pesos e novas exportações em lote.

## 1. Integridade dos artefatos — prioridade imediata

1. Reproduza no máximo três GETs com timeout do manifesto v2 por HTTP público e pela API S3. Registre status, horário e request-id; nunca credenciais. Compare com um objeto de controle. Não trate ETag como SHA-256.
2. Audite packages-v2/manifest.json: origem, schema, unicidade de nomes, ausência de caminhos absolutos/traversal, SHA-256 de 64 hex e tamanho de cada item. Um JSON válido de 22.483 bytes não basta. Confronte os 25 arquivos com a versão publicada, usando hashes independentes já confiáveis; onde faltarem, leia os bytes do objeto para calcular o hash. Não regenere hashes a partir de arquivos suspeitos para fazer o gate passar.
3. Salve manifesto e relatório verificados em novo prefixo experimental imutável. Prepare script de restauração com modo dry-run padrão, chave exata, hash esperado do candidato e verificação GET posterior. Não execute a substituição de models/granite/4.1-nar/v2/manifest.json sem autorização específica. Se o original for legível, preserve sua cópia antes; se ilegível, registre a limitação de rollback.
4. No código, substitua o mapa vazio silencioso por resultado explícito: manifesto remoto válido; cache local previamente validado da mesma versão; ou erro acionável. Downloads novos/atualizações exigem hash para TODO arquivo solicitado, incluindo external data e a variante LLM escolhida. Falta de rede não impede inferência de pacote já validado e instalado. Pacotes antigos sem evidência de integridade não podem ser rotulados como verificados.
5. Valide arquivos já existentes quando forem reutilizados; tamanho não nulo não prova integridade. Ative download somente após hash correto e mantenha pacote anterior utilizável se a atualização falhar. Feche conexões em finally. Verifique a rotina que apaga *.download no início: ela contradiz a retomada por Range; preserve parciais da mesma versão e identidade.
6. Testes focais: HTTP 500, JSON inválido, hash ausente/incorreto, cache de outra versão, arquivo existente corrompido, falha durante atualização e reinício de download parcial. Use fixtures pequenas e servidor falso/local.

## 2. Abertura sob demanda e contenção de falhas

Implemente e teste a criação de sessões conforme o bucket efetivamente necessário, eliminando o aquecimento automático t2000. Atualize a semântica de load/ready: inicializar metadados não prova que todas as sessões aceleradas abriram. Registre isso no protocolo e na UI, sem anunciar backend efetivo antes da execução.

Trocar o aquecimento por t0200 apenas deslocaria o crash para o primeiro áudio longo. Portanto implemente também política explícita de buckets/backends validados: em produção, o pacote NPU experimental continua indisponível; em debug, permitir somente t0200/t0400 sob o plano de teste. Não enviar t2000 quantizado ao DSP. Para bucket não aprovado, erro claro em modo estrito; CPU apenas quando a política do usuário permitir e com backend efetivo registrado por estágio. LLM no CPU + encoder na NPU deve aparecer como híbrido.

Garanta fechamento de sessões parciais em falhas, cache de sessões pequeno e ausência de corrida entre load/transcribe/release. Capture configuração por sessão, evitando que flags globais de debug vazem entre ensaios. Após SSR/crash, interrompa o grupo; só retome depois de comprovar recuperação com um controle conhecido. Sem loop de tentativas nem reinicialização automática do telefone.

Teste escolha de bucket, fronteiras de tamanho, arquivos faltantes, modo estrito, erro de sessão, cancelamento e ciclo repetido carregar/transcrever/liberar. Use o seam existente ou extraia um pequeno seam testável; não reestruture toda a Activity.

## 3. Qualidade: definições e corpus

Conserve texto bruto, hash do áudio, referência, texto normalizado, operações de edição, precisão e backend por estágio. Normalização versionada: Unicode NFC, minúsculas, espaços consolidados; ignorar pontuação puramente editorial. Preserve acentos, dígitos, palavras, repetições e negações. Não remova indiscriminadamente pontuação interna de números, datas ou palavras. Teste a normalização com exemplos positivos e negativos.

Separe dois objetivos:
- Paridade de implementação: CPU e NPU com exatamente os mesmos arquivos e entradas; compare texto normalizado, CTC e tensores.
- Qualidade ASR: WER/CER contra referência humana. Igualar CPU não prova que o áudio foi transcrito corretamente.

Triagem: seis áudios independentes com referência, PT obrigatório, pelo menos dois idiomas adicionais; inclua o áudio que produz “aprenderender”, t0200/t0400 e ocupações variadas. O corte de 3,5 s do mesmo áudio não conta como amostra independente. Acrescente silêncio como teste separado: não se exige texto não vazio para silêncio.

Compare A=CPU U8 + projector declarado + LLM int8b; B=mesmos artefatos, somente encoder na NPU. Faça A/B/B/A ou B/A/A/B balanceado por áudio. Para qualidade, repetições medem estabilidade, não aumentam o tamanho do corpus. Encerre investigação de promoção B se o smoke lexical falhar, mas execute o diagnóstico da seção 4.

Para o candidato CPU que passar na triagem, avaliar pelo menos 50 áudios independentes: mínimo 10 por idioma PT/EN/ES/FR/DE, variados em duração e dificuldade, separados da calibração. Incluir a regressão fr_fr_1597/T400 se recuperável; se não, marcar ausência, sem inventar aprovação. Validar CPU nos buckets grandes também, sem enviá-los à NPU. Comparar com a referência float confiável e ground truth.

Critério de candidatura: smoke normalizado idêntico à referência de implementação; nenhuma repetição artificial conhecida, saída vazia em fala inteligível ou regressão em nomes/números/negações no conjunto dirigido. No corpus, aumento absoluto de WER global <=1,0 ponto percentual; por idioma <=1,5 ponto; CER global <=1,0 ponto. Reportar microagregação por palavras/caracteres, macro por áudio, contagens e diferenças pareadas. Esses limites são gates de engenharia, não garantia estatística; 50 áudios ainda não certificam produção geral. Bootstrap pareado opcional, com seed, para mostrar incerteza; não esconder resultados inconclusivos. Relatar pontuação separadamente.

## 4. Localizar “aprenderender” antes de mudar precisão

O mesmo ONNX em CPU e HTP localiza a diferença no caminho de execução, mas não exclui interação com quantização, fusões, clipping ou parâmetros Q/DQ. “Ordem de acumulação do Hexagon” ainda é hipótese.

No áudio reprodutor, capture uma única vez os inputs reais do encoder e seus dois outputs em CPU e NPU. Confirme hashes dos inputs, máscaras, frames válidos e pesos. Instrumentação deve preservar configuração e partição do ensaio original; registrar eventual alteração da partição. Faça replay offline ou no aparelho com o mesmo projector CPU e LLM int8b:

1. BPE CPU + multilayer CPU (controle).
2. BPE NPU + multilayer CPU.
3. BPE CPU + multilayer NPU.
4. BPE NPU + multilayer NPU.

Recompute CTC, slots, comprimentos, posições e máscaras consistentemente para cada replay. Não reutilize embeddings/slots de um caso no outro. Reporte CTC colapsado, margens top1-top2 nos frames divergentes, NaN/Inf, cos/erro em features válidas, tokens do editor antes de collapse e texto final. Compare posições de texto separadas de áudio/padding: top1 global pode mascarar erro relevante.

Se a divergência se concentrar em uma saída/camada, proponha uma alteração focal baseada nela. Limite a esta matriz e no máximo um candidato corretivo; não iniciar nova busca de precisão em dezenas de blocos. Não testar encoder fp32 inteiro no HTP nesta rodada.

## 5. Contexto QNN mode=2 — teste que ainda falta

111–151 s para compilar não desqualificam, por si, um contexto pré-compilado. Essa hipótese merece um piloto t0400. Cache também não garante menor memória de execução nem texto melhor.

Use as opções do runtime realmente instalado. Referência: https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html#qnn-context-binary-cache-feature . As opções ep.context_enable, ep.context_file_path e ep.context_embed_mode são SessionOptions, não simples entradas do mapa debugQnnOptions do provider.

1. Compile uma vez encoder/projector mode=2, offload_graph_io_quantization=1, mantendo os demais parâmetros do caso que passou. Contexto somente desses grafos; LLM int8b permanece CPU. Falhar se não houver partição QNN esperada. Identifique Q/DQ de fronteira no CPU separadamente de fallback computacional.
2. Salve wrappers EPContext e todos os binários externos em diretório experimental. Hashes, versão ORT/QAIRT, SoC, firmware, shapes e opções compõem a identidade do cache. Não assuma portabilidade entre aparelhos/runtimes.
3. Feche sessões, force-stop e reabra explicitamente os wrappers gerados, sem recompilar o ONNX original. Faça três aberturas com processo novo e cache em disco preservado. Registre que cache de páginas do SO não foi necessariamente frio. Não use limpeza de caches privilegiada.
4. Meça compilação inicial, restauração do contexto por sessão, primeira inferência, inferência quente, PSS/pico observado, falhas DSP e texto. Compare exatamente os mesmos outputs/texto da execução sem cache. Uma aprovação de carga não é aprovação de qualidade.
5. Gate: três reaberturas sem crash/recompilação, texto equivalente ao grafo de origem e load total dos grafos acelerados <=10 s como meta inicial. Acima disso, registrar resultado e suspender otimização de carga nesta rodada. Mode=3 não entra, salvo falha específica documentada no cache de mode=2.

Mesmo se a qualidade continuar reprovada, guardar contexto como diagnóstico conhecido, nunca como candidato. Não expor “modo rápido” com corrupção lexical conhecida na UI.

## 6. Ensaio opcional de ArgMax — somente após as etapas anteriores

Se os relatórios confirmarem que o app só usa encoder_bpe_logits para argmax/CTC greedy, um piloto t0400 pode produzir IDs argmax no próprio grafo e conservar multilayer_features intacto. Isso reduz volume da saída, mas o tensor intermediário e lm_head podem continuar materializados no DSP; não prometa resolver o buffer de 174 MiB.

Verifique tie-breaking, eixo, dtype suportado e frames válidos. Teste IDs e CTC contra o grafo original em CPU e depois NPU, com mesmos inputs. Capture profiling/partições: ArgMax no CPU não comprova redução de transferência. Não trocar logits por FP16 nesta rodada, pois isso pode alterar empates e agravar a divergência. Não gerar outros buckets até o piloto provar correção e redução mensurável de custo/memória. Não testar t2000 NPU como consequência automática de t0400 passar.

## 7. Desempenho e encerramento

Use o melhor CPU aprovado como baseline, não fp16 histórico. Ensaio temporal separado da captura de tensores/profiling detalhado. Mesmos áudio, modelos, threads, carga/descarga, alimentação, temperatura e estado de sessões. ABBA balanceado com cooldown orientado por thermal status e faixa inicial de temperatura; bateria não substitui medição de throttling. No mínimo cinco inferências medidas por configuração em dois blocos independentes. Registre mediana e dispersão; não anuncie p95 confiável com cinco medidas.

Para NPU valer nova fase de produto: qualidade aprovada, ganho mediano end-to-end >=20% sobre CPU nos pares térmicos comparáveis, sem regressões relevantes por duração, e load com cache compatível com a meta. É meta desta fase, não previsão. Informe custo frio+primeira transcrição, quente e ponto de equilíbrio para reutilização das sessões.

Execute testes focais e gates :app:testDebugUnitTest, :app:lintDebug, :app:assembleDebug para o diff final, além do verificador MODULE-MAP aplicável. Registre falhas preexistentes separadamente. Commit apenas os arquivos próprios, sem bypass nem push. Não regenere pacote nativo se seu contrato não mudou.

Publique evidências sanitizadas em novo prefixo experimental imutável; áudio/tensores com dados pessoais ficam locais. GET e hash verificam publicação, HEAD sozinho não. Corrija a chave com barra inicial num novo manifest-v5, sem apagar o v4. Mantenha índices pequenos, scripts, manifests e resultados também em disco saudável para não repetir a perda do E:.

Entrega: DECISOES.json + relatório com status por fase (passed/failed/blocked/not-run), comando de reprodução, hashes, resultados por áudio, backend real por estágio, diff/commits e pendências. Produção do aparelho restaurada e verificada ao final de testes temporários. Se faltar telefone, execute código/testes offline e deixe comandos prontos; não marque testes de aparelho como aprovados.

Pare ao concluir esse escopo. Não reabra LLM-NPU, int4b, compartilhamento de pesos ou varredura de opções sem nova hipótese sustentada por evidência.

## 8. Roteiro operacional obrigatório: execução sem decisões implícitas

As instruções desta seção detalham as anteriores. Quando um recurso não existir, registre blocked na fase dependente e continue as fases independentes. Não invente comandos, flags, caminhos, suporte de operador ou resultados para preencher lacunas. Implemente os pequenos instrumentos de teste pedidos abaixo antes de executar o teste que depende deles.

### P0 — Preparação, antes de qualquer teste no aparelho

1. Localize a cópia efetiva do SIG no PC auxiliar. Leia AGENTS.md, MODULE-MAP.md, docs/relatorio-npu-u8-20260914.md e este prompt por inteiro. Se a skill sig-android-dev estiver disponível nessa máquina, leia também suas instruções aplicáveis; não assuma que ela existe nesta cópia.
2. Execute git status --short e git rev-parse HEAD; salve resultados em preflight/git.txt. Verifique se 78cf57c e 9138e03 existem e se as mudanças necessárias já estão no checkout. Não faça cherry-pick automático: elas podem já estar incorporadas por outros commits. Inspecione o código efetivo.
3. Crie um diretório novo, por exemplo <LAB_SAUDAVEL>/nar-next-20260914-<hora>, e um prefixo R2 experimental com o mesmo identificador. Resolva o caminho absoluto, espaço livre e escrita de um arquivo pequeno. Não enumere E: nem coloque caches/temp no E:. Não baixe todos os modelos antigos; reutilize apenas cópias com hashes conferidos.
4. Crie subdiretórios preflight, inventory, manifests, fixtures, quality, replay, context, performance, logs, handoff. Os arquivos grandes de captura devem ficar em replay, com tamanho estimado antes da execução. Não ponha modelos no Git.
5. Gere inventory/artifacts.json com role, bucket, variante, caminho, bytes, sha256, origem, external_data e estado de integridade. Para ONNX external data, confira cada caminho relativo e cada offset/length referenciado contra o arquivo real. Preserve nomes e subpastas esperados pelo grafo.
6. Registre versão de Python, pacotes, ORT real do processo, AAR usado no APK e QAIRT instalado. Não atualize dependências ou pacote nativo durante a rodada. Divergência de ambiente produz status incompatible-environment e uma explicação; não é resultado negativo do modelo.
7. Antes de instalar APK debug, confirme aparelho aprovado pelo serial e Android/SoC reais. Identifique o APK instalado e modo de restaurá-lo, variantes selecionadas e hashes dos arquivos de modelo tocados pelos scripts. Não use pm clear, uninstall ou limpeza global. Se não houver restauração segura, faça testes offline enquanto essa fase fica bloqueada.
8. Crie execution-plan.json com as fases P0–P9, estado pending e timestamp. Atualize atomicamente após cada fase; estado antigo passed só pode ser reutilizado se hashes de código, modelo, inputs e configuração coincidirem.

Saída: preflight e inventário completos. Não iniciar teste NPU se desconhecer o modelo que a sessão abrirá.

### P1 — Reparo de integridade e testes sem baixar gigabytes

Implemente primeiro os testes de contrato do manifesto usando arquivos de poucos KB. Audite também GraniteNarManifest: sua verificação não pode aceitar hash esperado ausente para um download novo. Se houver API legada que aceite ausência, mantenha-a fora do caminho de ativação validada e cubra a separação com teste.

Matriz mínima de testes automatizados:

| Entrada/falha injetada | Resultado obrigatório |
|---|---|
| Manifesto válido com todos os itens da variante | download permitido e hashes verificados |
| HTTP 500 sem cache | erro explícito antes de ativação; nenhum mapa vazio permissivo |
| HTTP 500 com cache validado da mesma versão | uso do cache identificado no log |
| Cache de outra versão ou identidade de pacote | recusa desse cache |
| HTTP 200 com JSON inválido | falha de manifesto, não aprovação parcial |
| JSON com item sem hash ou hash malformado | recusa para variante que exige esse item |
| Nomes duplicados com hashes conflitantes | recusa determinística |
| Caminho absoluto, ../ ou external data escapando da raiz | recusa antes de leitura/escrita externa |
| Arquivo existente com mesmo tamanho e bytes diferentes | hash detecta erro; arquivo não reutilizado como válido |
| Download truncado ou conexão interrompida | nenhuma ativação; parcial preservado quando identidade é válida |
| Retomada Range com 206 e Content-Range correto | anexar somente bytes faltantes e conferir hash final |
| Servidor responde 200 ao pedido Range | reiniciar esse temporário, sem concatenar conteúdo completo |
| 416 ou Content-Range inconsistente | validar se parcial já completo ou reiniciar com limite de uma tentativa |
| Atualização falha no terceiro arquivo | último pacote completo continua disponível |
| Inferência offline de pacote já validado | funciona sem consulta obrigatória ao manifesto remoto |

Guarde origem e identidade do parcial para impedir que bytes de outra versão sejam retomados. Um marcador de validação precisa ser invalidado se arquivo/pacote mudar; antes de promover ou reutilizar em ensaio, confira SHA completo. Não calcule SHA de gigabytes a cada inferência quente: isso alteraria o benchmark.

Saída: manifests/production-repair-assessment.json, cópia candidata, script dry-run de restauração e testes aprovados. Campo production_key_overwritten=false nesta tarefa. Se D1 ficar pendente de aprovação, isso não bloqueia P2–P9.

### P2 — Contrato de execução e seleção real de sessões

Inspecione os scripts existentes antes de invocá-los. Na cópia examinada, o smoke possui extras backend, require_full_acceleration, ort_verbose, llm_backend_cpu, projector_backend_cpu, warmup_bucket, qnn_opts, audio_path, run_id, warmup_runs, measured_runs, load_only e include_text. Eles não constituem, por si, um seletor de artefato experimental. Não invente um extra manifest_path que a Activity não lê.

Se os scripts existentes trocam arquivos na pasta normal, preferir acrescentar um caminho de pacote experimental exclusivo ao entrypoint debug, com validação da raiz permitida e identidade. Essa seleção não pode ser ativável externamente no APK release. Não duplicar todo o engine para isso. Se for inevitável trocar arquivos temporariamente, usar o protocolo existente apenas após snapshot/restauração verificada e nunca enquanto o engine mantém sessões abertas.

Estenda o protocolo com campos suficientes para comprovar cada execução:

```text
experiment_id, run_id, code_revision, apk_sha256, device_serial
audio_sha256, reference_id, real_frames, bucket_t, bucket_s
encoder_sha256, projector_sha256, llm_sha256, external_data_hashes
requested_backend_by_stage, configured_backend_by_stage
observed_execution_by_stage, partition_evidence_path
provider_options, session_options, load_mode(original/context)
status_execution, status_quality, skip_reason
text_raw_sha256, text_normalized_sha256, normalizer_version
session_load_ms_by_stage, first_inference_ms, warm_inference_ms
thermal_status, battery_temperature_c, power_state
```

Pode usar campos equivalentes existentes, desde que faça mapeamento explícito em protocol-contract.md. Não marcar observed_execution=NPU só porque addQnn foi chamado ou loadedBackend retornou NPU. Salvar evidência de partição/log/profiling por grafo; registrar Q/DQ de fronteira atribuídos ao CPU. Se modo estrito rejeitar essas operações, não simplesmente desative a restrição: diagnostique e registre quais nós impedem a prova. Resultados com computação relevante caindo silenciosamente no CPU são inválidos para a comparação NPU.

status_execution=passed significa sessão/inferência concluída; status_quality tem avaliação separada. O resultado agregado exige ambos quando há oracle. load_only pode ter quality=not-run, nunca quality=passed.

Teste o seletor de bucket com contagens de frames exatas: 199/200/201, 399/400/401, 799/800/801 e limite suportado. Esses casos são de regra pura; não executar todos no DSP. Não truncar fala para caber em bucket. Quando não houver bucket acelerado elegível, aplicar política explícita; nunca tentar o maior como fallback interno.

Prova da abertura sob demanda: após load, o log não mostra encoder/projector t2000 criado. Primeiro áudio de 175 frames abre t200; áudio de 354 frames abre t400. Testar a regra CPU e, separadamente, a política debug NPU. Áudio maior que t400 no debug NPU deve ser rejeitado antes de chamar o runtime. Testar release durante falha de criação e repetição load/release sem manter sessões antigas.

### P3 — Congelar corpus e normalizador

Crie fixtures/corpus-screening.jsonl antes das execuções. Cada linha contém id, idioma, audio_path, sha256, duração medida, frames calculados pelo frontend real, bucket esperado, referência humana, origem/licença e se pertenceu à calibração. Não derivar frames somente de arredondamento da duração.

Seleção determinística da triagem: dois PT, dois EN, dois ES; incluir o reprodutor PT de 7,08 s em uma das duas posições. Se o idioma real desse áudio for outro, conservar o reprodutor como caso adicional e manter seis independentes. Pelo menos dois áudios em t200 e dois em t400. Escolher fala completa com referências; se for necessário recortar, fazer corte em limite natural e revisar a referência correspondente. Silêncio de 2 s é fixture adicional, fora do denominador de fala. fr_fr_1597 é regressão adicional quando disponível.

O conjunto de 50 da fase ampliada pode incluir a triagem, mas deve declarar a sobreposição. Não reutilizar o corpus de calibração como única evidência de generalização. O relatório deve distinguir holdout de regressões usadas para ajustar candidatos. Se faltarem amostras independentes, baixar de fonte autorizada/licenciada ou registrar insufficent-coverage; não replicar arquivos para fechar 50.

Exemplos obrigatórios do normalizador e dos gates:

| Referência | Hipótese | Decisão de igualdade normalizada |
|---|---|---|
| viajar e aprender. | viajar e aprender | igual; diferença editorial bruta preservada |
| Viajar e aprender | viajar   e aprender | igual |
| aprender | aprenderender | diferente |
| não autorizou | autorizou | diferente |
| João | Joao | diferente, acento preservado |
| 1,5 mg | 15 mg | diferente, separador significativo preservado |
| casa casa | casa | diferente, nenhuma deduplicação automática |

Use distância de edição para WER/CER; registre substituições, inserções e exclusões. Não compare hipótese com ela mesma nem use o hash do candidato como oracle esperado. A referência humana e o baseline de implementação são objetos distintos.

### P4 — Rodada de qualidade e decisões automáticas

Configurações fixas:

| ID | Encoder | Projector | LLM | Finalidade |
|---|---|---|---|---|
| F | float confiável CPU | float confiável CPU | variante float de referência | qualidade histórica e referência de implementação |
| A | U8 CPU | versão CPU declarada e congelada | int8b-blk128 CPU | candidato prioritário |
| B | mesmo U8 de A, HTP mode=1 | mesmo de A, CPU | mesmo de A, CPU | isolar backend do encoder |
| C | U8 HTP mode=2 | projector U8 HTP mode=2 | int8b-blk128 CPU | contexto e pipeline híbrido |

Se A já usa projector U8, A/B/C podem compartilhar seu hash. Se A usa fp16, C muda também o projector: compare C com um controle A2=encoder U8 CPU + projector U8 CPU + mesmo LLM antes de atribuir diferenças ao backend. Não rotule A versus C como experimento de um fator quando os hashes diferem.

Para cada áudio de triagem, executar A/B/B/A; para o seguinte B/A/A/B. Reiniciar o processo entre configurações e registrar load separadamente. Nesta fase uma inferência por processo é suficiente para qualidade; não misturar seus tempos com a fase de desempenho quente. Registrar cada resultado imediatamente em JSONL para sobreviver a interrupção.

Se A difere lexicalmente de F, marcar falha de paridade e localizar o erro sem culpar NPU. Se A melhora contra ground truth mas falha na igualdade de implementação, registrar ambos e devolver para decisão humana; não alterar automaticamente o gate. Se B difere de A, executar P5, manter B não aprovado, e ainda permitir P6 como diagnóstico de cache.

Nunca converter 2/2 repetições em 100% de acurácia: são duas execuções de uma amostra. Publicar número de áudios únicos, repetições e idiomas em toda tabela de qualidade.

Rodada ampliada CPU: A e F no corpus de 50; repetições desnecessárias se determinismo já conferido. Caso F seja muito lento, execute-o offline apenas quando provar equivalência com o baseline do aparelho nas fixtures e usar exatamente a mesma cadeia. Caso contrário, medir F no aparelho ou registrar o gate incompleto. Não misturar referências de export diferente silenciosamente.

### P5 — Replay cruzado com limite de recursos

Faça o replay em laboratório saudável com artefatos e código fiéis. Capture tensores em arquivos separados com shape, dtype, endianness, comprimento e SHA. Estime RAM/disco antes: logits t400 podem ter dezenas de MiB; não serializar como JSON. Salve só a amostra reprodutora inicialmente e uma amostra controle se necessário.

Compare inputs antes de outputs. Se input_features não for bitwise igual entre A/B, corrigir o instrumento e repetir; o ensaio ainda não isolou backend. Guarde outputs brutos enquanto a memória ORT continua válida; copiar ou gravar antes de fechar OrtValue/Result. Não usar FloatBuffer depois de close.

Matriz de interpretação:

| Resultado do replay | Próxima conclusão permitida |
|---|---|
| Só BPE NPU reproduz repetição | investigar CTC/slots e margens de tokens nessa saída |
| Só multilayer NPU reproduz | investigar features recebidas pelo projector/editor |
| Só combinação reproduz | efeito combinado; nenhuma saída isolada é causa suficiente |
| Nenhum replay reproduz | investigar fidelidade do replay, estado e entradas; não concluir correção |
| Controle CPU já diverge | corrigir replay antes de interpretar os demais |

Uma intervenção corretiva focal só pode mudar os nós/tensores localizados, preservando os demais hashes/configurações relevantes. Se não houver localização suficiente, entregar diagnóstico e parar essa tentativa. Não atribuir o problema a acumulação, saturação ou máscara sem comparação que distinga essas hipóteses.

### P6 — Contexto: sequência exata e critérios de aborto

Começar com t200, se necessário para testar a instrumentação de EPContext; a medição principal é t400 mode=2. Não gastar tempo compilando t200 novamente se já existir contexto verificável da mesma configuração.

Sequência principal:

1. Baseline original mode=2 t400: load e uma inferência do reprodutor. Guardar texto/tensores disponíveis e evidência de backend.
2. Geração: usar caminhos diferentes para encoder_ctx.onnx e projector_ctx.onnx e os binários referenciados por cada um. Nunca permitir que a geração de uma sessão sobrescreva a outra. ep.context_enable=1; ep.context_embed_mode=0 quando suportado. Confirmar assinatura/schema dos arquivos efetivamente emitidos.
3. Timeout de geração: 600 s para o conjunto t400 desta configuração. No primeiro crash/SSR, interromper a configuração. Um timeout não justifica reiniciar indefinidamente. Liberar processo/sessões de teste e preservar logs.
4. Retirar sessões da memória, preservar arquivos. Reabrir explicitamente encoder_ctx.onnx e projector_ctx.onnx em processo novo, com geração desabilitada. Confirmar que a sessão não está recebendo os ONNX originais por um nome fixo do engine.
5. Repetir passo 4 três vezes. Timeout por restauração: 60 s, embora a meta seja <=10 s para os grafos acelerados juntos. Registrar também load total incluindo LLM CPU.
6. Em cada restauração, executar reprodutor e um controle e comparar com original mode=2. Se houver crash, erro de referência externa, recompilação ou alteração lexical causada pelo cache, rejeitar o contexto e não passar a desempenho.
7. Se passar, testar texto normalizado contra A/A2. Pode existir cache correto de um modelo com qualidade errada: nesse caso context_functional=passed, quality=failed e candidate=false.

Não mudar offload_graph_io_quantization para zero no mesmo ensaio; essa combinação já falhou no relatório. Não ativar compartilhamento de pesos. Encoder e projector são redes diferentes: não assumir que compartilhar contexto implicará pesos comuns ou resolverá ativações.

### P7 — Desempenho controlado e ponto de equilíbrio

Somente configurações com backend comprovado e qualidade adequada entram na tabela de candidatos. Tempos de configurações reprovadas podem constar em tabela diagnóstica separada, claramente marcados.

Use pelo menos um áudio t200 e um t400 aprovado no corpus. Para cada áudio e par A/B ou A2/C, executar dois blocos com ordem ABBA e BAAB. Cada letra significa processo novo, carga cronometrada, um warmup e cinco inferências medidas com sessões reutilizadas. Isso produz quatro sessões independentes por configuração, cinco amostras quentes por sessão. Não tratar as vinte inferências correlacionadas como vinte aparelhos independentes.

Condições de início: mesmo estado de alimentação, mesma orientação de tela/configuração, sem outros testes concorrentes; thermal status sem throttling reportado e temperatura inicial da bateria dentro de 1 °C entre os pares. Se após 15 minutos não conseguir restabelecer condição comparável, marcar bloco thermal-uncontrolled e suspender medição temporal. Essa faixa não prova igualdade de temperatura do SoC: registrar frequências/estado térmico expostos sem root quando possível. Não usar número de núcleos ocupados isoladamente para concluir que threads não importam.

Calcular:
- latência quente: mediana por sessão, depois resumo das sessões;
- ganho relativo = 1 - tempo_candidato / tempo_CPU, por áudio e bloco;
- primeira utilização = compilação/instalação necessária + restauração/load + primeira inferência;
- reutilização futura = restauração/load + primeira inferência, sem imputar recompilação inexistente;
- equilíbrio aproximado N = ceil(max(0, load_NPU - load_CPU) / (infer_CPU - infer_NPU)), somente quando o denominador for positivo. Reportar cenário com compilação inicial e cenário com contexto pronto separadamente.

Coletar PSS antes/depois de load e sequência, e pico amostrado em rodada diagnóstica separada se necessário. Não chamar dumpsys a cada poucos milissegundos no ensaio temporal. Fazer cinco inferências adicionais sequenciais para observar crescimento de memória quando houver suspeita de leak; crescimento isolado de caches não prova vazamento.

Se o ganho pareado não atingir 20%, ou variar de sinal entre blocos, entregar resultado insuficiente para promoção. Não aumentar orçamento procurando apenas o melhor número. Não promover mode=2 mesmo rápido se P4/P5 mantêm corrupção lexical.

### P8 — ArgMax opcional: gate de entrada obrigatório

Executar esta fase apenas se P0–P6 estiverem documentadas, o grafo original estiver íntegro, a instrumentação provar consumo exclusivo de logits por greedy CTC e não houver correção focal ainda em execução. Se o problema dominante for qualidade e não houver hipótese de benefício relevante na memória/transferência, pode marcar not-run com justificativa e encerrar.

Use um novo nome e novo schema de saída. Não substitua um arquivo que o app espera conter logits por outro contendo IDs. Adapte somente o consumidor debug ou contrato de pacote versionado; valide shapes/dtypes antes do acesso.

CPU: verificar IDs bitwise iguais ao argmax de referência nos seis áudios da triagem, inclusive empates sintéticos em teste unitário. NPU: executar primeiro t200 se o t400 não tiver caminho estável e depois t400; comparar com IDs calculados sobre logits do original no MESMO backend. Não confundir diferença CPU/HTP preexistente com alteração introduzida pelo ArgMax.

Se ArgMax não for suportado no caminho instalado, ou só executar no CPU, registrar e parar. Se reduzir bytes de saída mas não memória de pico/tempo, registrar exatamente isso. Não reabrir t2000 NPU nem autorizar lote com base nessa redução de bytes.

### P9 — Encerramento verificável

Entregar handoff/FINAL_REPORT.md e handoff/DECISOES.json. O relatório deve responder D1–D8 individualmente, listar pendências de produção e apontar exatamente os arquivos que suportam cada conclusão. Formato mínimo do JSON:

```json
{
  "experiment_id": "preencher",
  "code_revision": "preencher",
  "production_manifest_repair": "prepared|blocked|not-run",
  "production_key_overwritten": false,
  "cpu_candidate": "passed|failed|inconclusive|not-run",
  "npu_quality": "passed|failed|inconclusive|not-run",
  "context_functional": "passed|failed|blocked|not-run",
  "context_restore_ms": [],
  "npu_performance_gate": "passed|failed|inconclusive|not-run",
  "unique_audio_count": 0,
  "repetition_count": 0,
  "remaining_blockers": [],
  "production_promoted": false,
  "device_restoration_verified": false,
  "evidence_index": "preencher"
}
```

Os valores com barras são alternativas de schema: gravar apenas um valor real em cada campo. Não manter texto placeholder no arquivo final. zero só quando realmente zero; medida ausente é null ou not-run, nunca zero ms inventado. Incluir lista detalhada por fase se esse resumo não cobrir o resultado.

Antes de concluir: conferir git diff --check; testes/gates requeridos; hashes do pacote restaurado no aparelho, variante e preferências; ausência de processo experimental restante. Nunca reverter arquivos que outro agente alterou durante a rodada. Para restauração concorrente ou alvo diferente do snapshot, interromper apenas essa operação e comunicar conflito.

Publicação de evidências: primeiro inventário sanitizado, depois arquivos permitidos, finalmente manifesto imutável com todos os hashes e sem barra inicial nas keys. Se um upload falhar, registrar partial-publication e objetos faltantes. Não declarar 45/45 verificações se só 44 chaves foram conferidas.

Gere handoff/NEXT_COMMANDS.md contendo os comandos REALMENTE implementados e verificados por help/dry-run, com shell e diretório inicial. Evite comandos ilustrativos que parecem executáveis mas usam flags inexistentes. Liste também o comando de restauração de manifesto preparado, deixando inequívoco que ele ainda não foi executado.

## 9. Limite desta delegação e prioridades em caso de bloqueio

Ordem: P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8 opcional → P9. P1/P2/testes offline avançam sem telefone. Falha de qualidade NPU não impede candidato CPU nem teste diagnóstico de contexto. Falta de aprovação para sobrescrita de produção não impede os reparos revisáveis no código.

Não há orçamento autorizado para outra série aberta de paradigmas ou flags. Máximo: um diagnóstico cruzado, uma correção numérica focal sustentada por evidência, um piloto de contexto mode=2 t400, um piloto ArgMax opcional. Compatibilidade incerta deve ser resolvida com o menor teste possível, antes de exportação cara. Pare uma configuração no primeiro SSR; não faça tentativas repetidas que contaminem os controles seguintes.

Registre como hipótese, não fato: corrupção física do objeto R2; ordem de acumulação como causa do erro lexical; buffer de logits como causa exata do crash; temperatura da bateria como explicação exclusiva da variação; menor arquivo como menor consumo do DSP. Cada uma precisa da evidência específica correspondente.

O objetivo desta rodada é entregar um candidato CPU bem medido, downloads que não percam a validação silenciosamente, abertura segura de buckets e uma decisão NPU baseada em qualidade, cache e custo total. Não abrir modo rápido de produção com defeito conhecido de texto. Ao terminar, devolver evidências e código ao cérebro para revisão; não iniciar a rodada seguinte sozinho.

## 10. Autorização adicional — novos encoders, projectors e derivados

O usuário autorizou gerar mais arquivos quantizados ou outros artefatos necessários ao avanço. Pode exportar, quantizar, calibrar, gerar wrappers e contextos, baixar fontes/corpus necessários e publicar candidatos validados em prefixos experimentais novos. Não requer nova autorização para cada arquivo dentro deste escopo. Isso não autoriza sobrescrever produção, publicar APK ou transformar falha de qualidade em aprovação.

### 10.1 Regra para iniciar uma variante

Antes de cada geração, criar artifact-plan.json com: hipótese, evidência que a motivou, componente, bucket piloto, hash da fonte, transformação, precisão real de pesos/ativações/IO, configuração de calibração, runtime-alvo, comparação de controle, estimativa de disco/RAM e critérios de aprovação. Conferir artefatos existentes: se a variante idêntica já foi reprovada, não repeti-la sem uma diferença técnica documentada que possa mudar o resultado.

Use o menor bucket que reproduz o problema. Para “aprenderender”, preservar inicialmente t0400; mudar para t0200 cortando o áudio perderia o caso de teste. Mantenha fixos os demais estágios e a versão int8b do LLM para atribuir diferenças ao componente modificado.

### 10.2 Variantes permitidas e ordem condicional

1. **Encoder com ativação U16 e pesos U8:** permitido quando o replay indicar degradação na saída do encoder. Identificar primeiro o que a variante histórica chamada U16 realmente contém. Se houver export fiel compatível, reutilizá-lo e comparar com U8 antes de gerar outro. U16 não é garantia de maior precisão end-to-end, nem de caber no DSP. Validar CPU e depois HTP no mesmo input e bucket; preservar outputs/contrato e LLM/projector de controle.
2. **Precisão seletiva do encoder:** se a captura localizar tensors/nós sensíveis, aplicar overrides de quantização apenas neles, usando formatos aceitos pelo runtime-alvo. Não deixar regiões float caírem silenciosamente no CPU e chamar o resultado de encoder NPU estrito. Registrar as fronteiras Q/DQ e sua partição. Manter uma variante por hipótese, não combinações cartesianas de flags.
3. **Projector U8 ou U16/U8:** permitido para um bucket faltante, para comparar com o projector float ou quando P5 mostrar que o projector amplifica perturbações. Calibrar com multilayer_features reais e da seleção correta de camadas do encoder. Se usar features vindas do encoder HTP, registrar essa origem e reservar áudios distintos para validação. Não usar entradas aleatórias ou features de uma exportação incompatível.
4. **Encoder com saída ArgMax:** autorizado conforme P8. Artefato novo e contrato explícito, preservando multilayer_features. Redução de bytes de saída não basta para afirmar redução de memória intermediária. Essa variante pode coexistir com a correção numérica focal, mas cada transformação deve ser validada isoladamente antes de combiná-las.
5. **Buckets estáticos adicionais ou faltantes:** gerar somente quando o corpus ou a integração exigir uma forma que ainda não existe. Respeitar restrições reais do frontend, encoder e projector; não inventar um T arbitrário. Pad, frames válidos, comprimento do projector, slots e máscara do LLM precisam permanecer consistentes. Qualidade deve ser comparada ao baseline do mesmo bucket e também à referência humana para detectar efeitos do padding.
6. **LLM derivado para CPU:** permitido se a integração exigir um wrapper estático, external data íntegro ou bucket ausente da variante int8b aprovada. Preferir reutilizar pesos e método existentes, sem nova quantização. Preservar editor bidirecional, embeddings, posições e máscara. Este adendo não manda reabrir a pesquisa de LLM-NPU nem repetir QDQ/AWQ/SmoothQuant/GPTQ reprovados: para reabri-la, apresentar evidência nova de compatibilidade e uma hipótese distinta antes de gastar com o modelo inteiro.
7. **Contextos adicionais:** gerar para cada combinação de artefato/bucket efetivamente aprovada e necessária à matriz do aparelho. Mudanças de grafo, pesos, opções ou runtime invalidam o contexto anterior. Nunca renomear um contexto antigo para fazê-lo parecer compatível com uma nova variante.

### 10.3 Gates obrigatórios de cada artefato

G0 — Proveniência: fonte e external data íntegros, hashes, versões e script reproduzível. Preservar o original.

G1 — Estrutura: ONNX checker, shapes estáticos quando exigidos pelo QNN, inputs/outputs corretos, dados externos resolvidos, tipos/opset aceitos pelo runtime. Para o encoder, conferir seleção de camadas conforme a configuração da fonte, não assumir hs[-4:]. Para LLM, comprovar attention_mask presente e funcional e atenção bidirecional preservada.

G2 — CPU: abrir sessão, executar inputs reais, verificar NaN/Inf, paridade do contrato, tokens/CTC e texto normalizado. Usar áudio reprodutor e controles multilíngues. Um cosseno alto isolado não aprova quantização. Falha de texto implica diagnóstico; não ampliar buckets.

G3 — Aparelho: abrir somente bucket permitido, comprovar partição, executar o mesmo conjunto, registrar memória/preparação/texto. No primeiro SSR, parar a configuração. Aprovação CPU não autoriza rótulo NPU-approved.

G4 — Candidatura: aplicar gates de corpus e desempenho de P4/P7 à combinação completa que se pretende usar. Se múltiplos componentes forem aprovados isoladamente, validar a combinação: erros podem se acumular. Sem telefone, marcar phone_validation=false e status offline-validated, sem declarar contexto gerado quando ele não existir.

G5 — Publicação: publicar candidato em experiments/<novo-id>/, com manifesto imutável, arquivos externos completos, SHA-256, tamanhos, compatibilidade, configuração e resultados. Artefatos reprovados ficam locais quando úteis e seus diagnósticos pequenos podem subir. Não enviar gigabytes de variantes falhas por padrão. Reutilizar objetos de pesos já publicados somente quando seus bytes e referências forem de fato idênticos.

### 10.4 Expansão controlada e limites

Esta seção amplia a permissão anterior de uma única correção numérica: pode testar U16/U8, uma correção seletiva localizada e um projector corretivo, se cada hipótese tiver evidência. Execute sequencialmente. Se uma hipótese falhar, preserve o resultado e escolha apenas a próxima hipótese sustentada pelo replay; não iniciar busca aberta de hiperparâmetros.

Após o piloto passar G0–G3, pode gerar os buckets CPU necessários dentre 200/400/800/1200/1600/2000 e os projectors correspondentes, verificando cada um. Para NPU, continuar limitado a t0200/t0400 nesta rodada. Gerar arquivo t2000 para CPU não autoriza executá-lo no DSP. A promoção de buckets maiores à matriz HTP exige plano posterior baseado nas medições de memória.

Quando uma variante menor vencer a maior apenas por memória/latência, registrar o ganho sem presumir equivalência de qualidade. Nunca selecionar int4b com repetição lexical conhecida apenas porque é menor.

Inclua no relatório final uma tabela de TODOS os artefatos novos: componente, T/S, pesos/ativações/IO, tamanho, hashes, método, corpus/calibração, gates passados, backend comprovado, URL experimental e motivo de rejeição quando houver. Acrescente também os artefatos deliberadamente não gerados e a razão. O objetivo é permitir continuar a pesquisa sem repetir trabalho nem confundir um arquivo existente com um candidato aprovado.
