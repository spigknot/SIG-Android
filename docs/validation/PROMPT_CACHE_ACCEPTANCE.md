# Aceitação do prefixo de prompt

Este runbook verifica determinismo e orçamento local. Ele não simula nem declara
um cache hit do provedor.

## Teste automatizado

Os comandos abaixo são PowerShell. No Git Bash/MSYS, invoque `powershell.exe`
com `-File` em vez de executar o arquivo `.ps1` diretamente.

A suíte de fixtures deve passar silenciosamente:

    & .\scripts\tests\verify-agent-prefix.tests.ps1 -Quiet

Ela valida que:

- o AGENTS.md atual está dentro do orçamento;
- o mesmo conteúdo produz o mesmo fingerprint em execuções repetidas;
- um fixture com data, caminho local e credencial é rejeitado.

## Procedimento de evidência

1. Execute o verificador duas vezes sem editar AGENTS.md.
2. Compare contract, prefixFingerprint, prefixBytes e estimatedTokens.
3. Confirme que os quatro valores são idênticos.
4. Se a integração fornecer telemetria, acrescente cachedTokens,
   inputTokens e latencyMs em um registro redigido fora do Git.
5. Sem telemetria oficial, use telemetryStatus=unavailable e não converta a
   estabilidade do fingerprint em afirmação de hit.

O JSON local do verificador também pode conter status, budgetBytes e
forbiddenMatches para diagnóstico. Antes de compartilhar, projete somente os
campos redigidos acima e nunca inclua caminho local.

Para manter também o resultado dos gates, use a saída redigida do wrapper em
uma pasta ignorada, por exemplo:

    & .\scripts\validate-agent-harness.ps1 -RunAndroidGates -Quiet -Json -EvidencePath build/harness-validation.json

O conteúdo dinâmico da tarefa deve ser mantido depois do prefixo estático. Não
inclua datas, versões, resultados, chaves ou contexto de usuário no AGENTS.md.
