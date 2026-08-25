# Documentos do agente — índice sob demanda

AGENTS.md é o contrato global e deve permanecer pequeno. Carregue somente o
documento que corresponde à rota da tarefa.

- stt.md: ownership, hotspots e riscos do fluxo de transcrição.
- validation.md: matriz única de gates automatizados.
- release.md: separação entre desenvolvimento, pacote nativo e publicação.
- prompt-cache.md: fronteira estática/dinâmica e evidência redigida de cache;
  a aceitação operacional está em docs/validation/PROMPT_CACHE_ACCEPTANCE.md.
- docs/validation/STT_ACCEPTANCE.md: prova de campo REST/WebSocket.

Não replique comandos entre estes documentos. Quando houver conflito, a matriz
em validation.md é a fonte dos gates e UPDATE.md é a fonte do procedimento de
release.
