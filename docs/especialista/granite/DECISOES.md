# Decisões de arquitetura e evidências do Granite

Registro inicial de 09/10/2026, plano v1.0. Decisões prospectivas não mudam vereditos de rodadas antigas.

| ID | Decisão e status | Fundamento | Condição para rever |
|---|---|---|---|
| D01 | G01 corrige memória e tempo offline antes de coletar novamente | end já ocorre após release; before/after existem; delta N1 muda de sinal | Manifesto/extração demonstra ausência ou contaminação que exige prova nova específica |
| D02 | Parser manual é a primeira integração de produto pequena após G01 | Paridade experimental e gargalo de parse demonstrados | Vocab real diverge ou ganho/custo no APK integrado muda a utilidade |
| D03 | Integração por seams/hunks, sem copiar engine ou merge amplo | Worktree v3 diverge da main v11, UI/manifesto/instalador posteriores | Uma unidade completa pode ser adotada após resolver seus consumidores e provar ausência de regressão |
| D04 | ArgMax condicional à validação efetiva do consumidor | Helper de manifesto não consumido; shape/Long e fechamento precisam correção | Loader e testes reais fecham o contrato e manifesto/grafo/IDs concordam |
| D05 | Warm real requer sessão e bucket observados | load ignora parte do warmup CPU; t400→t200 recompila dentro do run; ciclos recarregam | Nova configuração imutável e eventos de sessão demonstram uma carga, warmup e medidas na mesma sessão |
| D06 | CTC CPU continua caminho inicial de rascunho | Menor carga observada; HTP ainda tem custo de sessão/memória e atribuição incompleta | Comparativo CTC×CTC controlado com contexto/partição e break-even beneficia uso real |
| D07 | H8 preservado como candidato de precisão; Q4 permanece experimental | H8 textos iguais no conjunto observado; Q4 piora WER contra REF e economiza memória | Confirmação independente com limites de produto definidos antes da coleta |
| D08 | EXTENDED/ALL_OPT não reabrem em lote | ABBA anterior não ganhou consistentemente e alterou texto | Mudança concreta de runtime/grafo com hipótese localizada e piloto pequeno |
| D09 | GPU QNN antigo não encerra OpenCL/Vulkan | Falhas 6022/6020 são de artefatos/versões específicos | Novo runtime/representação tem suporte concreto e bloco noncausal numericamente correto |
| D10 | Smart NAR por estágios; Smart literal é rota AR própria | Editor bidirecional sem decode token a token; AR tem tokenizer/cache próprios | Outra arquitetura demonstrada com identidade e qualidade próprias; nunca causalizar NAR como equivalência |
| D11 | Upstream ORT e plugin QNN são braços distintos | Dependências Android/QAIRT e distribuição não coincidem automaticamente | Matriz congelada prova compatibilidade de combinação específica |
| D12 | Sem limpeza/descarte neste trabalho | Artefatos/lab têm valor de reprodução; espaço D limitado | Inventário e decisão explícita por conjunto, com recuperação/ownership definidos |

Hipóteses refutadas no histórico: Android 16 impede universalmente FastRPC sem root; nome fp16 prova dtype; rótulo NPU prova execução integral; título sessão reutilizada prova warm; end mede memória antes de release; ausência de amostra no extrator prova ausência no stream; extensão/bits declarados provam quantização; referência do modelo é verdade humana. O [histórico auditado](HISTORICO-EVIDENCIAS-20261008.md) limita cada refutação aos seus dados.

Novas decisões acrescentam linha com data, evidência e escopo. Não remover hipóteses refutadas nem trocar critérios depois de olhar o resultado para aprovar o mesmo candidato.

## Adições da revisão v2 — 20/10/2026

| ID | Decisão e status | Fundamento | Condição para rever |
|---|---|---|---|
| D13 | Rebaseline F00 antes da nova campanha | main e alterações locais diferem do snapshot de 09/10; disponibilidade de aparelho mudou | Inventário/parecer F00 fixa a base efetiva |
| D14 | Dossiê Hy-MT2 transfere metodologia, não comandos nem aprovação | Documento é referência para Whisper; encerra com kernel/Smart sem aprovação global | Evidência nova identificada pode informar decisão Granite própria |
| D15 | Smart principal é encoder HTP e editor bidirecional OpenCL/Vulkan | Arquitetura NAR de passagem única | Modelo AR separado na ordem FAR; nunca mudar causalidade do NAR para imitar Hy-MT2 |
| D16 | Áudio longo primeiro sobre janela validada; 60 s é investigação independente | Chunking local de 40 s ainda sem aceitação real; T3000/S maior mudam custo/contrato | F03/F04 demonstram cobertura/qualidade/memória e teto por perfil |
| D17 | Janela de arquivo, T, S e contexto interno são dimensões distintas | Código frontend/export e config oficial | Outro frontend/modelo exige nova derivação versionada |
| D18 | Deduplicação global ou igualdade lexical isolada não aprova junção | Repetições legítimas podem ser removidas | Alinhamento/ownership e corpus dirigido comprovam a estratégia de fronteira |
| D19 | MNN é primeiro piloto comum GPU, sem promessa de suporte NAR | Conversão e APIs OpenCL/Vulkan disponíveis no projeto oficial | Bloqueio mínimo concreto justifica alternativa, com controle idêntico |
| D20 | HTP começa com sonda e buckets pequenos protegidos | Histórico de crash t2000 e compatibilidade de runtime específica | Prova isolada/parecer por bucket e versão |
| D21 | Provar backend efetivo por estágio e fallback | Labels, bibliotecas e provider registrado não são execução | Evidência de partição/profiling completa fecha a atribuição |
| D22 | Atenção fundida/Flash será avaliada no backend Android real | Pacote FlashAttention CUDA/ROCm não é implementação Adreno pronta | Kernel equivalente correto e dispatch real permitem ablação |
| D23 | Comparação por qualidade/cortes iguais e por configuração de produto em braços separados | Evita atribuir a hardware mudança de modelo/chunk/padding | Novo desenho pode melhorar controle sem perder atribuição |
| D24 | Critérios numéricos/qualidade/performance prospectivos, antes da confirmação | Evita selecionar tolerância após resultado; corpus CPU não é verdade humana | Revisão de critério exige nova versão e não reaprova legado |
| D25 | A→B→A e gerações íntegras obrigatórios na integração | Pedido original de cache/progresso e novas variantes de artefato | Contrato diferente precisa preservar persistência e rollback |
| D26 | Uma fase por despacho, relatório e parecer | Fluxo explicitamente pedido pelo usuário | Usuário altera o fluxo; prompts futuros continuam condicionais até lá |


## Adições da revisão v3 — 29/10/2026

As decisões abaixo atualizam o escopo; linhas anteriores permanecem como histórico. D27 substitui a parte OpenCL de D09/D15/D19. D29 atualiza a situação do patch citada em D16.

| ID | Decisão e estado | Fundamento | Condição para rever |
|---|---|---|---|
| D27 | OpenCL e Smart NPU→OpenCL suspensos; F05 sem prompt v3 | Pedido explícito do usuário | Nova decisão explícita do usuário |
| D28 | F06 contém seleção/conversão/bloco/piloto completos, sem F05 | Vulkan deve avançar de forma independente | Bloqueio mínimo pode abrir a reserva ExecuTorch Vulkan |
| D29 | Rebaseline em HEAD 254b4a2, pacote v14; chunks já commitados | Código e histórico a2b4d58 | Novo inventário F00 registra evolução sem apagar evidência |
| D30 | 60 s e auditoria histórica ampla fora do caminho crítico dos pilotos/live | Áudio longo usa janela comprovada; prova nova pode ser independente | Uma alegação específica pode exigir histórico/shape de 60 s |
| D31 | F08 requer encoder HTP e editor Vulkan aprovados, não NPU integral | Particionamento NAR por estágios | Artefato ou contrato incompatível exige correção própria |
| D32 | Validar máscara/oráculo antes de conversão e atenção fundida | Card e código oficial exigem atenção à semântica bidirecional | Nova implementação oficial exige nova prova versionada |
| D33 | Convivência com Whisper/ORT exige identidade de libs, owner e ACK físico | Carregador e recursos compartilhados; cancel visual não encerra compute | Arquitetura isolada comprovada pode reduzir a matriz |
| D34 | Live por janelas NAR é extensão F13–F16, sem causalizar modelo | Pedido explícito de transcrição ao vivo como no Whisper | Outro modelo/arquitetura depende de decisão própria |
| D35 | Reaproveitar componentes live somente com contrato e regressão | PCM/spool/publicação existem; timestamps/decoder não são intercambiáveis | Auditoria F13 define adaptações necessárias |
| D36 | Qualificação live separada de arquivo, com backlog/latência/qualidade | RTF isolado não inclui frequência de reprocessamento | Critério revisado prospectivamente, nunca para aprovar o mesmo resultado |
| D37 | Spool limitado preserva áudio pendente; prévia obsoleta não é áudio descartável | Integridade e experiência contínua | Mudança de retenção precisa de contrato explícito |
| D38 | Download/cache A→B→A e progresso por identidade permanecem gates | Pedido original e novos artefatos/caches | Revalidação por todos os consumidores do componente alterado |
| D39 | 16 fases/128 tarefas preparadas; uma ordem e parecer por vez | Processo de execução pedido pelo usuário | Usuário altera o fluxo |
