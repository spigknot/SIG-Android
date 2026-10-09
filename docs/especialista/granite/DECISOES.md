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
