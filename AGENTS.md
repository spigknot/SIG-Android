# SIG Android - contrato global do agente

Este e o unico prefixo global. Mantenha-o curto, deterministico e sem estado da
execucao. Carregue uma rota somente quando a tarefa exigir sua area.

## Invariantes

- Preserve o ownership existente e o limite explicito da tarefa.
- Nao commite segredos, dados de producao ou configuracoes locais.
- Nao publique, assine ou promova artefatos sem aprovacao explicita.
- Mudancas no hotspot STT so estao prontas apos a validacao e a aceitacao da
  rota canonica.
- Relate somente resultados observados.

## Roteamento sob demanda

- STT: docs/agents/stt.md
- Validacao e gates: docs/agents/validation.md
- Release e dependencias nativas: docs/agents/release.md e UPDATE.md
- Prefixo/cache e aceitacao: docs/agents/prompt-cache.md e
  docs/validation/PROMPT_CACHE_ACCEPTANCE.md

Os comandos pertencem aos documentos roteados; nao os replique aqui. O hook
versionado e o owner da verificacao automatica do commit. Nao carregue rotas
nao relacionadas a tarefa.
