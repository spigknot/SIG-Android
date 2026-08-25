# Prefixo estável e prompt caching

## Contrato

AGENTS.md é o prefixo global. Seu conteúdo deve conter somente invariantes
duráveis, ownership de alto nível e ponteiros para documentos sob demanda.

O prefixo estático não deve conter:

- datas, números de versão, tags, hashes de uma execução ou estado de branch;
- caminhos absolutos, nomes de usuário, letras de unidades ou detalhes do host;
- chaves, tokens, endpoints privados, áudio, dados de produção ou payloads;
- saídas de testes, tempos, diagnósticos ou decisões específicas de uma tarefa;
- cópias de comandos e tabelas que já tenham um owner documental.

O conteúdo dinâmico deve ficar na tarefa, no diff, nos resultados de comandos e
nas evidências redigidas. Não transforme uma saída dinâmica em regra global.

## Verificador

O script scripts/verify-agent-prefix.ps1 normaliza o texto para UTF-8 sem BOM,
LF e sem espaços à direita para calcular bytes, estimativa de tokens e SHA-256.
Ele também rejeita padrões de datas, caminhos locais e credenciais no prefixo.

A estimativa de tokens é apenas uma métrica determinística de orçamento; não é a
telemetria do provedor. O fingerprint é do conteúdo canônico e não inclui o
caminho do arquivo.

Execute:

    & .\scripts\verify-agent-prefix.ps1 -Quiet -Json

Para validar a estabilidade como procedimento de aceitação, carregue também
docs/validation/PROMPT_CACHE_ACCEPTANCE.md. Este arquivo define as comparações
repetidas e a projeção redigida; não copie o runbook para AGENTS.md.

O orçamento padrão é intencionalmente pequeno. Se uma invariável realmente
precisar entrar no prefixo, revise o custo e atualize os fixtures antes de
aumentá-lo.

## Evidência redigida

O verificador pode provar estabilidade do prefixo local. Ele não prova que o
provedor aplicou prompt cache. Só registre métricas de cache quando a
telemetria oficial da integração as fornecer; caso contrário, marque a
telemetria como unavailable e não infira hit ou economia.

Uma evidência compartilhável pode conter somente:

    contract
    prefixFingerprint
    prefixBytes
    estimatedTokens
    budgetBytes
    telemetryStatus
    cachedTokens, quando fornecido pelo provedor
    inputTokens, quando fornecido pelo provedor
    latencyMs, quando fornecido pelo provedor

Nunca registre prompt efetivo, conteúdo de usuário, chave, token, áudio, header,
payload ou caminho local em um artefato compartilhado.

O gate central aceita EvidencePath para gravar um JSON redigido em uma pasta
local ignorada, por exemplo build/harness-validation.json. O caminho não é
incluído no JSON; compartilhe apenas o conteúdo depois de confirmar que não há
dados externos ao contrato.

## Rotina de mudança

Ao editar AGENTS.md:

1. execute scripts/tests/verify-agent-prefix.tests.ps1 -Quiet;
2. execute scripts/validate-agent-harness.ps1 -Quiet;
3. revise o diff para remover conteúdo volátil ou repetido;
4. somente depois rode os gates da rota afetada.

Sessões diferentes podem reutilizar o prefixo quando o texto canônico e sua
ordem forem iguais. O fingerprint local permite detectar divergência; a
confirmação de cache hit continua dependente da telemetria do provedor.
