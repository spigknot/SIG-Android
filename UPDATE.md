# UPDATE.md — Procedimento completo de geração de nova versão (SIG Android)

> Este documento é a FONTE DA VERDADE para gerar e publicar uma nova versão do SIG Android.
> Siga EXATAMENTE esta ordem. Cada passo tem comandos literais, verificações e os
> pitfalls já vividos. Se um passo falhar, NÃO pule — resolva conforme a seção
> "Pitfalls e resolução".

---

## 0. Visão geral do fluxo

```
bump da versão (3 lugares) → build (test+lint+assemble) → APK no O:\ → commit+push → GitHub (sig.apk, só a atual) → verificação SHA-256
```

- **Distribuição do APK**: GitHub Releases (`spigknot/SIG-Android`), asset `sig.apk` — SEMPRE a versão atual (release anterior é deletada).
- **Dependências nativas** (ffmpeg/whisper/silero, baixadas na 1ª execução): Cloudflare R2, SEMPRE no bucket `sig-android` (`https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/sig-android-dependencies-v1-<abi>.zip`). O Google Drive está APOSENTADO para o app novo (APKs antigos ainda usam o Drive — não mexer nos arquivos de lá enquanto houver APKs antigos em campo).
- **Credenciais R2**: usar um `release/r2_config.json` local e ignorado pelo Git (modelo em `release/r2_config.example.json`), com apenas `endpoint`, `access_key_id` e `secret_access_key` da chave dedicada ao bucket `sig-android`. Não gravar tokens `cfat...` no projeto.
- **Verificação de atualização**: o app consulta `releases/latest` do GitHub na abertura (silencioso) e compara com o `APP_VERSION` embutido — por isso o bump do `APP_VERSION` é OBRIGATÓRIO a cada versão.

## 0.1 Contexto essencial

- **Repositório**: use a raiz retornada por `git rev-parse --show-toplevel`; o procedimento principal usa PowerShell 5.1+ ou PowerShell 7. Git Bash continua suportado para o hook e para os comandos Git, mas não deve interpretar arquivos `.ps1` diretamente. No Git Bash, invoque o host explicitamente, por exemplo: `powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/validate-agent-harness.ps1 -Quiet`. Não dependa de uma letra de unidade fixa.
- **Branch**: `main` (commit + push direto na main).
- **Versão nova**: a versão atual está em TRÊS lugares (todos precisam subir juntos):
  1. `app/src/main/java/br/gov/sp/pcsp/launcher/AppUpdateChecker.kt` → `const val APP_VERSION = "YYYYMMDD_NNN"`
  2. `app/build.gradle` → `versionCode` (incrementar em 1)
  3. `app/build.gradle` → `versionName` (incrementar em 1)
- **Formato da versão**: `YYYYMMDD_NNN` (mesma data, número sequencial seguinte; ex.: se está `20260823_001`, gere `20260823_002`). O `APP_VERSION` usa underscore; a comparação do checker ignora `_`/`-` (tags antigas como `20260817-002` continuam válidas).
- **APK de saída**: `app/build/outputs/apk/debug/app-debug.apk`.
- **Destino opcional**: `O:\sig.apk` (MESMO nome sempre), somente quando a unidade estiver montada. O destino principal é o GitHub.
- **Contrato Java/Gradle**: o wrapper usa Gradle 9.7.0 conforme `gradle/wrapper/gradle-wrapper.properties`; launcher e daemon usam JDK 21 conforme `gradle/gradle-daemon-jvm.properties`. O app compila com toolchain e bytecode Java 17 conforme `app/build.gradle`. Configure `JAVA_HOME` para JDK 21.

## 0.2 Regras obrigatórias (não negociáveis)

1. **Gate completo automático**: o hook versionado `.githooks/pre-commit` executa `testDebugUnitTest`, `lintDebug` e `assembleDebug` em modo silencioso e bloqueia o commit se o exit code não for `0`. Com `-Staged`, o wrapper bloqueia alterações tracked fora do índice e entradas não rastreadas que possam entrar no build antes de executar o Gradle; assim os inputs de código do build ficam alinhados ao snapshot staged. Para executar antes do commit ou no CI, use o wrapper documentado em `docs/agents/validation.md`; para diagnóstico detalhado, repetir o Gradle sem `--quiet`. NUNCA publicar com FAIL.
2. **Bump em 3 lugares** (seção 2) — esquecer o `APP_VERSION` quebra o update checker (o app novo se ofereceria a própria versão como atualização).
3. **APK no GitHub SEMPRE**: asset com o nome exato `sig.apk`, tag = `YYYYMMDD_NNN` (a release anterior é deletada — regra "só a versão atual").
4. **APK no O:\** com o nome fixo `sig.apk`; falha de envio para O:\ → ignorar e seguir (menos importante).
5. **Verificar o SHA-256 do asset publicado** contra o build local (lição: o GitHub já serviu APK velho — nunca confiar em data/aparência).
6. **Commit + push na main ANTES de criar a release** do GitHub.
7. **NUNCA embutir chaves de API** no código/APK (campos nascem vazios; o usuário digita nas configurações). Não commitar `local.properties`, `release/r2_config.json` nem `app/src/main/assets` com segredos.
8. **NUNCA `System.load` manual** das libs do pacote nativo (SIGSEGV) — o FFmpegKit carrega via `sig.native.library.dir`. `smart-exception-java 0.2.1` é OBRIGATÓRIO no build.gradle (vacina `FfmpegKitClasspathTest`).
9. Não inventar resultados nem números: tudo que for reportado deve vir da saída real dos comandos.
10. Se QUALQUER etapa falhar: PARE imediatamente e reporte o erro exato (mensagem + o comando que falhou), sem tentar contornar por conta própria fora deste documento.
11. Ao terminar, revise e atualize este documento se algo divergiu (seção 9 — Manutenção do documento).
12. `git commit --no-verify` pode contornar o hook local; o CI continua sendo a segunda barreira obrigatória.

## 1. Pré-requisitos (antes de começar)

1. **`gh` autenticado** como `spigknot`: `gh auth status` (se falhar: `gh auth login`).
2. **Java/Gradle OK**: o build usa o wrapper `gradlew.bat` em Gradle 9.7.0 e JDK 21 (nada a instalar além do ambiente Android exigido pelo Gradle).
3. **Repositório limpo para começar**: `git status --short` — se houver mudanças não commitadas legítimas, editar em cima delas; nunca `git reset`/`checkout --`/limpeza ampla.
4. **Hook local**: o clone principal já está configurado com `core.hooksPath=.githooks`; em um clone novo, executar uma única vez `& .\scripts\install-git-hooks.ps1 -Quiet` no PowerShell.
5. **Credenciais R2**: manter `release/r2_config.json` local com a chave dedicada ao bucket `sig-android`. Copiar somente `endpoint`, `access_key_id` e `secret_access_key`; o upload deve forçar `bucket = sig-android` e nunca reutilizar `bucket`/`public_base` de outro projeto.

## 2. Bump da versão (3 lugares, SEMPRE juntos)

Calcular a próxima versão: mesma data de hoje, número sequencial seguinte (ex.: `20260823_001` → `20260823_002`).

```powershell
# 2.1 AppUpdateChecker.kt (a versão que o update checker compara)
#     editar: const val APP_VERSION = "YYYYMMDD_NNN"
# 2.2 build.gradle — versionCode += 1 (ex.: 27 -> 28)
# 2.3 build.gradle — versionName (ex.: "1.464" -> "1.465")
```

Confirmar que a versão NOVA é a MESMA nos 3 lugares.

## 3. Build (gate completo)

```powershell
$root = (git rev-parse --show-toplevel)
Set-Location $root
& .\scripts\validate-agent-harness.ps1 -RunAndroidGates -Quiet -Json
if ($LASTEXITCODE -ne 0) { throw "gate Android falhou" }
```

- **Critério de sucesso**: exit code `0`. O hook repete este gate automaticamente no commit. NÃO é sucesso se houver qualquer `FAIL`/`e:`; para investigar, repetir o Gradle diretamente sem `--quiet`.
- ⚠️ **Nunca encadear** `Copy-Item`/`commit` com uma cadeia que esconda o exit code. Checar o exit code ANTES de prosseguir.
- APK gerado: `app/build/outputs/apk/debug/app-debug.apk`.

## 4. APK no O:\ (opcional — falha pode ser ignorada)

```powershell
$root = (git rev-parse --show-toplevel)
Set-Location $root
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path -LiteralPath "O:\") {
    Copy-Item -LiteralPath $apk -Destination "O:\sig.apk" -Force
    Write-Output "APK copiado"
} else {
    Write-Output "O:\ indisponivel; destino opcional ignorado"
}
```

- **Mesmo nome sempre** (`sig.apk`).
- Se o `Copy-Item` falhar (unidade desmontada): **ignorar** e seguir para o commit — o destino importante é o GitHub.

## 5. Commit e push (na main)

```powershell
$root = (git rev-parse --show-toplevel)
Set-Location $root
git add -A
git commit -m "Versao YYYYMMDD_NNN: <descrição curta>"
git push origin main
```

- Não commitar: `local.properties`, chaves, `.gradle/`, `build/` (verificar `git status` antes do add se necessário).

## 6. GitHub Releases (asset `sig.apk` — SEMPRE renomear)

```powershell
$root = (git rev-parse --show-toplevel)
$apkDir = Join-Path $root "app\build\outputs\apk\debug"
Set-Location $apkDir
Copy-Item -LiteralPath "app-debug.apk" -Destination "sig.apk" -Force
gh release create YYYYMMDD_NNN "sig.apk" --repo spigknot/SIG-Android --title "SIG Android YYYYMMDD_NNN" --notes "<descrição>"
```

- **O nome do asset é o nome do arquivo local**: subir `app-debug.apk` cria um asset `app-debug.apk` (errado). SEMPRE renomear para `sig.apk` antes.
- Se a release ficar em draft (upload interrompido): `gh release edit YYYYMMDD_NNN --repo spigknot/SIG-Android --draft=false`.
- **Regra "só a versão atual"**: deletar a release anterior:

```powershell
gh release delete <VERSAO_ANTERIOR> --repo spigknot/SIG-Android --yes
```

- ⚠️ Execute `gh release delete` e `gh release create` como passos separados; uma tag anterior inexistente não deve impedir a criação da nova release.

## 7. Verificação final (antes de declarar pronto)

1. `gh release list --repo spigknot/SIG-Android` → só a versão nova, marcada Latest.
2. `gh release view YYYYMMDD_NNN --repo spigknot/SIG-Android --json assets` → asset `sig.apk` presente.
3. **SHA-256 do asset publicado == build local** (obrigatório):

```powershell
$root = (git rev-parse --show-toplevel)
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$url = "https://github.com/spigknot/SIG-Android/releases/download/YYYYMMDD_NNN/sig.apk"
$remoteBytes = (New-Object Net.WebClient).DownloadData($url)
$sha = [Security.Cryptography.SHA256]::Create()
$remote = ([BitConverter]::ToString($sha.ComputeHash($remoteBytes))).Replace("-", "").ToLowerInvariant()
$sha.Dispose()
$local = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
Write-Output "asset : $remote"
Write-Output "local : $local"
Write-Output $(if ($remote -eq $local) { "OK" } else { "DIVERGE!" })
```

4. `git status` limpo (push feito).
5. O `APP_VERSION` do APK novo == a tag da release (senão o update checker se ofereceria a própria versão).

## 8. Entrega (relatório final obrigatório)

Ao concluir, reportar APENAS valores reais das saídas dos comandos:

1. A versão publicada (`YYYYMMDD_NNN`).
2. O resultado do build (exit code `0` + testes/lint OK).
3. O link da release do GitHub.
4. O hash do commit (`git rev-parse HEAD`).
5. O SHA-256 do asset vs local (`OK` ou `DIVERGE!`).
6. Se o APK foi copiado para O:\ ou se foi ignorado (falha da unidade).

Se QUALQUER etapa falhar: PARE imediatamente e reporte o erro exato (mensagem + o comando que falhou), sem tentar contornar por conta própria fora deste documento.

## 9. Manutenção do documento (obrigatório)

Ao terminar, revise este `UPDATE.md`: se QUALQUER passo divergir do que foi
documentado, ou se você encontrou um pitfall novo (erro, atalho, detalhe de
ambiente), ATUALIZE este documento para refletir a realidade e inclua o
pitfall na tabela de resolução — no mesmo commit da versão. Este documento é
a fonte da verdade e deve evoluir com a prática.

---

## Pitfalls e resolução (já vividos — não repetir)

| Sintoma | Causa | Resolução |
|---|---|---|
| `'return' is prohibited here` no Kotlin | `return` não-local dentro de lambda de `use { }` | usar `return@use` (retorno local ao lambda) |
| `Thread { }` não executa | faltou o `.start()` (build passa, bloco nunca roda) | `Thread { ... }.start()` sempre |
| Asset na release com nome errado (`app-debug.apk`) | `gh release upload` usa o NOME DO ARQUIVO LOCAL | renomear para `sig.apk` antes do upload |
| APK da release "antigo" (usuário baixou versão velha) | upload substituiu? não — criou asset novo; ou subiu build velho | verificar SEMPRE o SHA-256 do asset vs build local (seção 7.3) |
| `gh release delete` impede o `create` seguinte | cadeia com `&&` corta no primeiro erro (tag inexistente) | separar passos ou usar `;` |
| Build "passou" mas commit saiu quebrado | cadeia de comandos engole o exit code | checar o exit code ANTES do Copy-Item/commit (seção 3) |
| App se oferece a própria versão como atualização | `APP_VERSION` do `AppUpdateChecker.kt` não foi bumpado | bump nos 3 lugares (seção 2) |
| Ferramentas ffmpeg falham no aparelho | pacote nativo ausente/corrompido; ou `smart-exception-java` removido do build.gradle (NoClassDefFoundError em runtime, build passa) | manter `com.arthenica:smart-exception-java:0.2.1`; ver logcat `SigNative`; o download nativo vem do R2 na 1ª exec |
| `AccessDenied` no bucket R2 `sig-android` | credencial de outro projeto ou escopo incorreto | usar a chave dedicada ao bucket `sig-android`; as credenciais do SIG Windows e do SIG Android são diferentes |
| Upload aparece no bucket errado | configuração compartilhada trouxe `bucket`/`public_base` de outro projeto | usar somente as credenciais S3 e forçar `bucket = sig-android` e a URL `pub-6476622beda24c82875cb84f11f660ea.r2.dev` |
| `RequestTimeTooSkewed` no R2 | relógio do Windows dessincronizado (w32time parado; >15 min de diferença) | `powershell.exe -NoLogo -NoProfile -NonInteractive -Command "Start-Service w32time; w32tm /resync"` (elevação); comparar `(Get-Date).ToUniversalTime().ToString('R')` com `(Invoke-WebRequest -Uri https://api.cloudflare.com -Method Head).Headers.Date` |
| ZIPs de dependências locais com SHA diferente do código | `build/native-dependencies/` tem reconstruções locais; a fonte validada é o que está publicado | subir SEMPRE os arquivos que batem com os SHA-256 do `NativeDependencyManager.kt` (baixar do Drive/R2 atual e conferir antes de subir) |
| `O:\` desmontada no Copy-Item | unidade de rede indisponível | ignorar (destino menos importante); seguir com commit/GitHub |
| `-Staged` parece validar um build staged | o Gradle usa a árvore de trabalho, então o wrapper precisa impedir divergência nas entradas de build | conferir `stagedConsistencyPolicy` e `androidGatesScope`; o guard bloqueia tracked unstaged e entradas Gradle não rastreadas |
| Runbook executado no shell errado | um `.ps1` chamado diretamente no Bash é interpretado pelo Bash | usar os blocos PowerShell; no Git Bash chamar `powershell.exe -File scripts/<script>.ps1`; resolver a raiz com `git rev-parse --show-toplevel` |
| Dependência nativa baixando do Drive | `NativeDependencyManager.kt` com URL antiga | usar `https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/sig-android-dependencies-v1-<abi>.zip` |

---

## Contexto da transição (histórico)

- `20260817-002`: última versão com numeração antiga (`YYYYMMDD-NNN` com hífen) e dependências no Google Drive.
- `20260823_001`: primeira com a numeração do Windows (`YYYYMMDD_NNN`), dependências nativas no Cloudflare R2 (bucket `sig-android`), verificação de atualização via GitHub (`AppUpdateChecker`), e publicação "commit + push + APK no GitHub + O:\" como regra permanente.
- O Drive mantém os ZIPs de dependências por um tempo (APKs antigos ainda apontam para lá); quando não houver mais APKs antigos em campo, os arquivos do Drive podem ser removidos.
- Um APK antigo (sem o update checker) só atualiza manualmente; um APK novo (com o checker) avisa na abertura quando `releases/latest` for maior que o `APP_VERSION` dele.
