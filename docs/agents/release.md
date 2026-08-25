# Release e dependências nativas

Leia este documento somente para tarefas de pacote, release ou publicação.

- UPDATE.md é a fonte da verdade do fluxo de versão, build, commit, push,
  release e verificação do APK.
- UPDATE.md usa PowerShell e resolve a raiz pelo Git; destinos de rede são
  opcionais e não devem ser tratados como parte do caminho principal.
- NativeDependencyManager.kt é o owner da versão, URL, tamanho e SHA-256 dos
  pacotes nativos por ABI.
- O build rápido assembleDebug não compila o pacote nativo por desenho.
- Para gerar ou verificar o pacote nativo, use os scripts e a matriz em
  docs/agents/validation.md.
- Em automação, use os modos -Quiet -Json dos verificadores; repita sem -Quiet
  somente para diagnóstico detalhado de uma falha.
- Credenciais permanecem em arquivos locais ignorados ou no armazenamento
  seguro; nunca entram em código, APK, log ou commit.
- APK e pacote nativo só podem ser promovidos juntos após os verificadores
  aceitarem e o usuário aprovar explicitamente.

Não copie números de versão, endpoints, tags ou resultados de uma execução para
AGENTS.md. Esses valores são voláteis e pertencem ao procedimento de release
carregado sob demanda.
