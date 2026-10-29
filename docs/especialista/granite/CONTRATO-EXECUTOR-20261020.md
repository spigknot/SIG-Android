# Contrato de execução e evidência Granite v2

Vigente para os prompts de 20/10/2026. Este contrato detalha como realizar a fase efetivamente recebida; não despacha outras fases. O pedido humano e o AGENTS aplicável prevalecem. Documentos de campanhas Hy-MT2/Whisper são referências técnicas, sem transferir autorizações, comandos ou restrições de dispositivo.

## 1. Início de cada ordem

1. Ler o prompt integral, este contrato, AGENTS.md do checkout e o parecer da fase anterior. Confirmar o ID/revisão da ordem no relatório. Se o parecer alterar seu escopo, aplicar a versão mais recente explicitamente emitida.
2. Registrar Git HEAD, branch, status, hashes dos arquivos que serão tocados e diffs preexistentes. Não executar reset/clean/stash amplo nem incluir trabalho alheio no patch.
3. Revalidar caminhos e ferramentas com comandos reais; documentação antiga menciona E: e outros diretórios. Usar caminhos absolutos obtidos no ambiente. Não inventar flags de scripts.
4. Trabalhar em branch codex/ identificada e base revisável. Se o checkout estiver contaminado por trabalho alheio, usar checkout/worktree apropriado e transferir somente os hunks deliberadamente aceitos. A alteração local de chunks de 40 s é entrada a auditar na fase 03, não código para descartar.
5. Criar diretório único da fase e tentativa. Separar fontes versionáveis de modelos, áudios, logs e binários grandes. Não lançar uma matriz enquanto não houver um piloto curto válido.
6. Listar tarefas do prompt em coverage.csv: ID, realizada/pendente/não aplicável, evidência, motivo. Não omitir tarefa difícil do relatório.

Não há obrigação de instalar um pacote só porque aparece numa lista de candidatos. Inventariar, definir hipótese e executar o menor teste capaz de decidir. Não reabrir falha histórica sem diferença concreta de grafo, runtime, dispositivo ou contrato.

## 2. Escopo e recursos

Executar alterações e experimentos reversíveis necessários à fase despachada. Usar modelos/corpus públicos com licença compatível e cache existente antes de novos downloads. Não usar áudio policial, chaves de API ou dados privados de outras campanhas como fixture.

Escolher o aparelho pelo inventário atual e pela ordem vigente. Serial/IP históricos não são seleção automática. Antes de instalar, reservar a janela do serial e conferir modelo/SoC/ABI/Android/fingerprint/UID e pacote alvo. Se houver outro dono ou uso humano que invalide a medição, realizar trabalho offline e registrar a dependência. Não tomar exclusividade por existir uma conexão ADB.

Usar probe privado ou build de desenvolvimento apropriado, sem apagar dados ou substituir produção por conveniência. Não ativar root, overclock, governor fixo ou mudanças térmicas para obter um número melhor. Capturar evidência antes de limpar buffers; os logs de outras tarefas não são descartáveis.

Uma carga grande por vez até provar folga de RAM; um dono por DSP/serial; uma edição ativa por fonte. Limites iniciais: novos artefatos até 20 GiB em C e logs até 1 GiB em D por rodada, com pelo menos 50 GiB/10 GiB livres respectivamente. Se uma tarefa exigir mais, entregar estimativa e proposta concreta ao especialista, sem apagar históricos.

Um piloto de runtime tem orçamento inicial de um dia de trabalho até o relatório. Começar com operador e bloco real, até dois candidatos por rodada. Registrar tentativas malsucedidas; após corrigir causa ambiental demonstrada, permitir nova tentativa específica. Repetição da mesma falha sem nova hipótese não justifica outro lote.

Após crash/SSR do DSP, coletar diagnóstico e interromper a bateria desse recurso. Não fazer reboot/retry em loop. Se recuperação exigir ação destrutiva ou aparelho em uso, entregar o que for independente e informar o impedimento.

## 3. Identidade da configuração

Cada tentativa terá um run_id único e configuration_id derivado de configuração canônica. Incluir:

| Grupo | Campos mínimos |
|---|---|
| Código | Commit/base, patch hash, árvore suja, build flags, toolchain |
| App | Application ID, variante, versionCode/name, APK SHA-256, ABI, UID |
| Nativos | Caminho efetivamente carregado, SHA de cada .so/.skel relevante, runtime/build/driver, HTP arch |
| Modelo | Família/revisão, pesos e external_data por SHA/tamanho, grafo por estágio, precisão efetiva, licença |
| Contrato | Inputs/outputs, dtype, dimensões, strides/layout, escalas, mask, posições, blank/vocab |
| Execução | Rota solicitada e observada por estágio, threads/afinidade se usada, bucket T/S, strict/fallback |
| Cache | Chave, contexto/generation ID, hit/miss, criação/reuso de sessão, compilação e warmup |
| Entrada | Áudio por SHA, duração/amostras, normalização/frontend, plano de cortes por SHA |
| Ambiente | Serial, SoC, Android/driver, alimentação, temperatura/bateria, horário UTC e monotônico |

Nem a versão AAR no Gradle nem o nome fp16/quant/NPU certifica o arquivo carregado ou a execução. Obter identidade real e partição. Para uma fase puramente offline, campos não aplicáveis são null com motivo; não inventar APK.

Caches de sessão/contexto devem incluir identidades necessárias à compatibilidade, inclusive runtime/SoC/driver/grafo/precisão/bucket/I/O. Invalidar somente o incompatível. Cache de modelo é persistente por integridade; cache de ativações de um áudio nunca satisfaz outro.

## 4. Contratos numéricos e oráculos

Fixar referência independente a partir do modelo fonte revisado, de preferência PyTorch no host com revisão/dtype/atenção explícitos, mais ORT CPU como controle do export. Não usar o código de conversão ou do candidato para calcular a referência do mesmo defeito.

Provar em camadas: frontend → encoder/CTC → projector → montagem de embeddings → editor → IDs/texto. Registrar tamanho, finitos, máximos, erro absoluto/relativo, NMSE quando apropriado e divergência de IDs. Para diferenças FP16/BF16/quantizadas, preregistrar tolerância por estágio, baseada em controle de arredondamento e sensibilidade. Se isso não estiver definido, coletar piloto e voltar ao especialista antes de afirmar paridade.

Checar explicitamente:

- Atenção bidirecional do editor; comportamento de posições válidas e padding; GQA/RoPE; attention_multiplier e escalas de embeddings/logits/residual.
- Offsets de áudio/texto, comprimentos válidos, interleave e blank; pooling/ceil/floor; ausência de truncamento quando T/S excedem o bucket.
- Dtype e layout reais, tamanho em bytes e ownership de buffers. Converter INT64 para Int somente após validar faixa e formato.
- Na transformação logits → ArgMax, IDs exatos para o mesmo grafo/precisão, política de empate e comportamento diante de não finitos; testar saída e consumidor integrados.
- Fechamento de tensors/results/sessions/contextos em sucesso, erro, cancelamento e troca de configuração; consumo fora do lifetime deve falhar no teste.

Negativos devem atingir o caminho de produção do checker/runner: SHA errado, dimensão/layout trocado, scale incorreta, máscara causal, offset/blank errado, dtype incompatível, replay de outro run, fallback em strict, timeout, child exit nonzero e saída stale. O teste deve mostrar qual gate rejeitou o mutante. Um programa que apenas retorna 1 não testa nenhum contrato.

Byte-identidade é necessária em cópia sem conversão. Se há conversão de precisão/layout, exigir a conversão de referência declarada e seu erro medido; hash sozinho não prova equivalência numérica.

## 5. Partição e atribuição de hardware

Para cada estágio neural, fornecer evidência de API/backend e cobertura: relatório de partição/compilação, profiling/despachos ou mecanismo equivalente do runtime. Registrar nós/ops e bytes transferidos, não só quantidade de nós; um nó MatMul pode dominar milhares de operações de shape.

Em strict, qualquer fallback neural não permitido reprova a tentativa daquela rota. Pré/pós-processamento host declarado pode ficar em CPU. Em modo produto, fallback explícito pode salvar a transcrição, mas seu tempo pertence à rota efetiva e não pontua como sucesso da rota solicitada.

Quando o runtime não permite observar execução suficiente, classificar atribuição como não comprovada. Não inferir aceleração de uso geral da GPU, mensagem “provider registered”, menu, biblioteca presente ou velocidade isolada.

Para Smart, provar encoder HTP e editor na API GPU indicada dentro da mesma tentativa. Identificar projector, CTC e montagem. O custo de sincronização, quantização, cópia e troca de contexto entra no total.

## 6. Qualidade e corpus

Construir três partições disjuntas por falante/arquivo de origem: calibração, exploração e confirmação reservada. Usar português brasileiro prioritariamente, diversidade de voz, ritmo, ruído, duração, números/nomes e repetições. Registrar proveniência/licença e política de uso; nenhum dado privado entra em Git.

O conjunto reservado formal deve ter pelo menos 50 enunciados/clipes, 3000 palavras de referência e 30 minutos no total, incluindo arquivos maiores e fronteiras dirigidas. Se não houver material adequado, registrar insuficiência; não duplicar o mesmo áudio ou concatenar para fingir diversidade. Combinações artificiais podem testar cobertura, mas não substituem o corpus de qualidade.

Fixar normalização WER/CER e seu SHA antes de medir. Publicar ambas, agregação micro e por amostra, I/D/S e distribuição por cenário. A referência humana pode conter erro: adjudicar casos contestados com rastreabilidade sem alterar somente os casos que favorecem o candidato.

Gates propostos contra CPU validada sobre as mesmas referências:

| Categoria | Limite de regressão | Uso |
|---|---|---|
| Equivalente | ΔWER e ΔCER ≤ +0,5 pp | Mesma qualidade nominal; backends/atenção/ArgMax |
| Intermediário quantizado | Ambos ≤ +1 pp | Perfil distinto com benefício demonstrado |
| Compacto | ΔWER ≤ +3 pp e ΔCER ≤ +2 pp | Perfil optativo, nunca vendido como equivalente |

Nenhuma categoria admite erro crítico novo atribuível ao candidato no conjunto dirigido: troca/omissão de negação, número ou nome de interesse previamente definido, perda/duplicação de segmento, mistura entre áudios. Comparar com controle e referência para não culpar backend por erro já presente no modelo.

Usar intervalos de confiança pareados por arquivo/falante; com amostra pequena a conclusão pode ser inconclusiva mesmo com média dentro do limite. Definir estes critérios antes da confirmação. Não recalibrar com o conjunto reservado depois de observar seus resultados.

## 7. Tempo, memória e energia

Métricas calculadas dos eventos brutos, com unidades e intervalos:

- load_ms, compile_ms, warmup_ms, frontend_ms, encoder_ms, projector_ms, assembly_ms, editor_ms, ctc_ms, bridge_ms, merge_ms, output_ms, inference_ms e operation_wall_ms; evitar somar intervalos sobrepostos.
- audio_seconds, processed_unique_samples, chunk_count, RTF=operation/infer time explicitado dividido pela duração, RTFx=inversa; real-time só se o intervalo correspondente for menor que o áudio.
- Memória before_load/after_load/before_infer/after_infer/after_release e amostras durante a execução. Marcar pico como observado por amostragem quando esse for o método.
- RSS/PSS, Java/nativo e GPU/DSP quando observáveis; unidades KiB/MiB ou bytes explícitas. Ausência é null, não zero.
- Temperatura, thermal status, bateria e fonte de energia antes/depois e durante séries longas. Joules apenas com método medido; proxy de bateria não é energia em J.

O evento end pós-release não mede memória pós-inferência. Warm requer mesmo PID/sessão/bucket/grafo e ausência de nova compilação; reload não é warm. Cold-process e cold-context são condições distintas. Não apagar caches de perfis alheios para simular cold.

Piloto usa n pequeno com valores individuais. Confirmação de performance usa pelo menos 5 pares válidos por candidato e cenário, ordem balanceada/randomizada com semente, temperatura inicial comparável e exclusões pré-definidas. Registrar todas as tentativas/exclusões, sem escolher a mais rápida. Mediana e dispersão para n=5; p95 exige no mínimo 20 medidas e método claro, sem alegar garantia estatística.

Realizar dois braços: cortes idênticos para atribuir ganho ao backend e melhor configuração validada de cada rota para utilidade de produto. Modelo/qualidade diferentes recebem categoria própria. Meta inicial para recomendação: ≥15% de redução mediana no tempo total ou ≥20% de redução de memória com latência adequada à categoria, sustentada pela incerteza e qualidade.

## 8. Áudio longo e cancelamento

Plano de cortes imutável por run, em amostras inteiras, com conteúdo/contexto e propriedade de cada intervalo. Provar cobertura completa da entrada e ausência de lacunas; sobreposição de contexto não conta como áudio único adicional.

Testar em limites ±1 amostra e ±1 frame, silêncio, fala contínua, fronteira em palavra, repetição legítima, múltiplas janelas e horas representativas sem acumular PCM/tensores proporcionalmente ao total. A duração longa escolhida deve caber no orçamento; ausência de prova de horas não permite afirmar duração ilimitada.

Não deduplicar sobre a transcrição inteira. Política de junção precisa de testes contra perda e duplicação. Quando a fronteira for ambígua, não remover texto só por igualdade lexical. Timestamps só podem ajudar se forem reais e validados.

Cancelamento deve impedir novo chunk, finalizar/recolher recursos com prazo observado e marcar parcial. Em falha, resultado parcial não vira sucesso completo; retomada só reutiliza chunks compatíveis por SHA de entrada/modelo/cortes e sua integridade. Progresso nunca deve chegar a 100 antes da conclusão efetiva.

## 9. Artefatos e reprodução

Organização recomendada, adaptando caminhos na fase 00:

~~~text
C:/Users/Gustavo/SIG-granite-experimentos/20261020/Fxx/<attempt_id>/
  input-manifest.json
  configuration.json
  commands.jsonl
  events.jsonl
  raw/                     logs/capturas sem edição
  tensors/                 somente pilotos necessários, com licença
  partition.json
  metrics.json
  quality.json
  exclusions.jsonl
  checks.txt
  manifest.json
  report.json
  RELATORIO-FASE-XX.md
~~~

O manifesto registra schema/version, ID da tentativa, paths relativos, papel, tamanho e SHA-256 completo. Não incluir .pyc, cache transitório, segredo ou o próprio manifesto numa recursão de hash. Verificar arquivos→manifesto e manifesto→arquivos; extras não declarados precisam ser classificados/rejeitados pelo contrato do bundle.

Fechar os arquivos numa ordem sem ciclos: inputs/logs/metrics → relatório Markdown e report.json → manifest.json. O índice artifacts do report.json referencia os inputs/resultados anteriores; não inclui o próprio report.json nem o manifesto final. O manifesto final inclui os relatórios e todos os arquivos entregues anteriores, exceto ele próprio. Seu SHA fica em recibo externo ou arquivo detached excluído do conjunto hashado. Uma referência textual ao path do manifesto não precisa carregar seu hash dentro do relatório.

Tentativas são imutáveis após fechamento. Correção gera nova tentativa com supersedes e motivo; nunca sobrescrever falha com sucesso. Montar staging e publicar a geração inteira/ponteiro de forma atômica. Não promover artefatos para bucket público só para facilitar o relatório.

commands.jsonl guarda argv/cwd, variáveis relevantes redigidas, UTC/monotônico, timeout, RC real do processo filho e paths de stdout/stderr. Capturar RC imediatamente. Se wrapper falhar em coletar, a tentativa é inválida; arquivo existente não substitui execução. Scripts PowerShell devem distinguir stderr informativo do ADB de exit code, sem transformar nonzero em sucesso.

Reprodução inclui dependências fixadas, comandos completos e resultados esperados. Para um arquivo de vários GiB, calcular hash uma vez e reusar registro só enquanto tamanho/mtime/identidade forem imutáveis; a promoção exige SHA confirmado.

## 10. Verificação de código e encerramento

Fazer primeiro testes focais relevantes. Mudanças em Kotlin/Android exigem na base final:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
~~~

Obedecer AGENTS para hotspot RemoteSttActivity, teste novo do fluxo async quando tocado, KDoc e MODULE-MAP de fontes novos. Manter lógica pura nos seams. Não criar testes espelhando implementação quando não medem risco; aqui os testes devem provar contrato, numérica, fronteira e recuperação.

Para ferramentas Python, executar os testes da unidade e a suíte pertinente de tools/granite/nar/test_nar_tools.py, sem introduzir imports com efeito colateral. Para harness, usar a suíte -Quiet e gates previstos. Antes de commit autorizado, gate staged em árvore isolada/coerente, sem bypass de hook nem stage de arquivos alheios. Esta ordem não implica push de qualquer mudança.

Debug assemble não recompila nativos. Mudança nativa exige build e verificação do pacote conforme AGENTS antes de uma promoção conjunta. Sem mudança de pacote, reutilizar ZIPs publicados. Nunca assinar/publicar/promover sem a aprovação explícita exigida pelo AGENTS.

Ao encerrar: relatório por [modelo](MODELO-RELATORIO-FASE-20261020.md), report.json conforme [schema](SCHEMA-RELATORIO-FASE-v2.json), coverage.csv, patch/revisão, manifesto e reprodução. Declarar o que foi provado e o que permanece desconhecido. Não marcar o programa ou a próxima fase como executados. Retornar ao especialista e aguardar sua revisão, conforme o fluxo solicitado pelo usuário.
