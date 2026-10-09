# Validação da entrega documental do Granite

Data: 09/10/2026, São Paulo. Escopo: plano, primeira ordem, estado e auditorias; nenhum fonte de produção foi alterado nesta entrega.

Checkout isolado: `C:\Users\Gustavo\.codex\worktrees\granite-plano-20261008\SIG`, branch `codex/granite-plano-20261009`, base `21439199ed28086139c2bbbe64ce4e4e18de77d9`. Cópias dos documentos ficam também em `D:\Projetos\SIG\docs\especialista\granite`. Os arquivos de outras tarefas no checkout principal ficaram fora da entrega e do índice.

| Verificação | Resultado | Alcance |
|---|---|---|
| Schema do snapshot, links locais e hashes conhecidos | Passou; três inputs conhecidos conferidos por SHA-256 | Identidade dos arquivos indicados, não todos os pesos do lab |
| Revisão por código/evidências/runtimes | Concluída e correções incorporadas | Consistência do plano, ordens, critérios e fontes |
| `validate-agent-harness.tests.ps1 -Quiet` | Exit 0, sem diagnóstico de falha | Suíte contratual do harness; execução na main preservada |
| Gate sem Android na main | Passou diff-check e module-map | Consistência observada; sem staged snapshot da árvore alheia |
| Gate staged no checkout isolado | Passou staged-snapshot, diff-check e module-map | Somente documentos próprios no índice, base alinhada |
| `:app:testDebugUnitTest` | 56 suítes; 580 testes registrados, 576 executados, 4 ignorados, 0 falhas, 0 erros | Base Android limpa mais documentos; não houve inferência Granite em aparelho |
| `:app:lintDebug` | Exit 0; 1113 warnings no relatório, sem issues de erro | Warnings da base, nenhum código Android alterado |
| `:app:assembleDebug` | Exit 0 | Build rápido; não compila nem regenera pacotes nativos |

Os quatro testes ignorados são de `NativeDepsOfficialFixturesTest`, dependentes de ZIPs oficiais reais. Os pacotes nativos não mudaram e não foram regenerados para esta entrega de documentos. O resultado não representa aceitação nova desses ZIPs.

Os comandos Android foram executados juntos, com SDK conhecido:

```powershell
$env:ANDROID_HOME = 'C:\Users\Gustavo\AppData\Local\Android\Sdk'
.\gradlew.bat --quiet :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Resultados XML: `app/build/test-results/testDebugUnitTest/TEST-*.xml` e `app/build/reports/lint-results-debug.xml`, no checkout isolado. A sessão de teste observada utilizou JDK `C:\Users\Gustavo\.jdks\ms-17.0.16`; Java 21 também está instalado no host. Distinguir versão instalada e ferramenta efetivamente usada.

APK de build local: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `398b84f7fceca55411d4dc27c111ff1ece49604aec96635a58f0cd64c128628e`. Ele não foi instalado, promovido ou publicado. É diferente do APK observado no CPH2747; essa observação não estabelece proveniência do APK do telefone.

O hook normal permanece ativo e aplica o gate staged com Android ao commit documental. Não houve bypass. O versionamento desta entrega é restrito à branch documental, sem commit dos arquivos alheios no checkout principal.

G01 está pronta como documento, mas não foi enviada a um executor externo. A identidade desse executor continua pendente; o pedido prevê encaminhamento da primeira ordem como documento nessa situação. Não há experimento, download, benchmark ou monitoramento em execução por causa desta entrega.
