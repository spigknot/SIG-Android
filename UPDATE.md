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
- **Dependências nativas** (ffmpeg/whisper/silero, baixadas na 1ª execução): Cloudflare R2, SEMPRE no bucket `sig-android` (`https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/sig-android-dependencies-v1-<abi>.zip`). Os ZIPs são versionados separadamente e NÃO são regenerados a cada release somente do APK. O Google Drive está APOSENTADO para o app novo (APKs antigos ainda usam o Drive — não mexer nos arquivos de lá enquanto houver APKs antigos em campo).
- **Credenciais R2**: usar um `release/r2_config.json` local e ignorado pelo Git (modelo em `release/r2_config.example.json`), com apenas `endpoint`, `access_key_id` e `secret_access_key` da chave dedicada ao bucket `sig-android`. Não gravar tokens `cfat...` no projeto.
- **Verificação de atualização**: o app consulta `releases/latest` do GitHub na abertura (silencioso) e compara com o `APP_VERSION` embutido — por isso o bump do `APP_VERSION` é OBRIGATÓRIO a cada versão.

## 0.1 Contexto essencial

- **Repositório**: `D:\Projetos\SIG` (Windows; o terminal é bash/MSYS — caminhos `C:/...` viram glob no `gh`, use `cd` no diretório e caminhos relativos `./arquivo`).
- **Branch**: `main` (commit + push direto na main).
- **Versão nova**: a versão atual está em TRÊS lugares (todos precisam subir juntos):
  1. `app/src/main/java/br/gov/sp/pcsp/launcher/AppUpdateChecker.kt` → `const val APP_VERSION = "YYYYMMDD_NNN"`
  2. `app/build.gradle` → `versionCode` (incrementar em 1)
  3. `app/build.gradle` → `versionName` (incrementar em 1)
- **Formato da versão**: `YYYYMMDD_NNN` (mesma data, número sequencial seguinte; ex.: se está `20260823_001`, gere `20260823_002`). O `APP_VERSION` usa underscore; a comparação do checker ignora `_`/`-` (tags antigas como `20260817-002` continuam válidas).
- **APK de saída**: `app/build/outputs/apk/debug/app-debug.apk`.
- **Destino O:\**: `O:\sig.apk` (MESMO nome sempre). O `O:\` é unidade de rede que às vezes está desmontada — se o `cp` falhar, IGNORAR (é o destino menos importante) e seguir com o GitHub.

## 0.2 Regras obrigatórias (não negociáveis)

1. **Build com o gate completo**: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — os TRÊS juntos (assembleDebug sozinho prova só compilação). Critério: `BUILD SUCCESSFUL`. NUNCA publicar com FAIL.
2. **Bump em 3 lugares** (seção 2) — esquecer o `APP_VERSION` quebra o update checker (o app novo se ofereceria a própria versão como atualização).
3. **APK no GitHub SEMPRE**: asset com o nome exato `sig.apk`, tag = `YYYYMMDD_NNN` (a release anterior é deletada — regra "só a versão atual").
4. **APK no O:\** com o nome fixo `sig.apk`; falha de envio para O:\ → ignorar e seguir (menos importante).
5. **Verificar o SHA-256 do asset publicado** contra o build local (lição: o GitHub já serviu APK velho — nunca confiar em data/aparência).
6. **Commit + push na main ANTES de criar a release** do GitHub.
7. **NUNCA embutir chaves de API** no código/APK (campos nascem vazios; o usuário digita nas configurações). Não commitar `local.properties`, `release/r2_config.json` nem `app/src/main/assets` com segredos.
8. **NUNCA `System.load` manual** das libs do pacote nativo (SIGSEGV) — o FFmpegKit carrega via `sig.native.library.dir`. `smart-exception-java 0.2.1` é OBRIGATÓRIO no build.gradle (vacina `FfmpegKitClasspathTest`).
9. **Pacote nativo é condicional**: em release somente do APK, quando `NativeDependencyManager.kt` e o contrato dos ZIPs não mudaram, NÃO gerar, verificar localmente ou republicar ZIPs. Se houver mudança nativa, executar o fluxo de geração/aceitação antes de promover APK ou pacote.
10. Não inventar resultados nem números: tudo que for reportado deve vir da saída real dos comandos.
11. Se QUALQUER etapa executada falhar: PARE imediatamente e reporte o erro exato (mensagem + o comando que falhou), sem tentar contornar por conta própria fora deste documento.
12. Ao terminar, revise e atualize este documento se algo divergiu (seção 9 — Manutenção do documento).

## 1. Pré-requisitos (antes de começar)

1. **`gh` autenticado** como `spigknot`: `gh auth status` (se falhar: `gh auth login`).
2. **Java/Gradle OK**: o build usa o wrapper `./gradlew` (nada a instalar).
3. **Repositório limpo para começar**: `git status --short` — se houver mudanças não commitadas legítimas, editar em cima delas; nunca `git reset`/`checkout --`/limpeza ampla.
4. **Credenciais R2 quando houver publicação nativa**: manter `release/r2_config.json` local com a chave dedicada ao bucket `sig-android`. Copiar somente `endpoint`, `access_key_id` e `secret_access_key`; o upload deve forçar `bucket = sig-android` e nunca reutilizar `bucket`/`public_base` de outro projeto. Uma release somente do APK não exige upload R2.

## 2. Bump da versão (3 lugares, SEMPRE juntos)

Calcular a próxima versão: mesma data de hoje, número sequencial seguinte (ex.: `20260823_001` → `20260823_002`).

```bash
# 2.1 AppUpdateChecker.kt (a versão que o update checker compara)
#     editar: const val APP_VERSION = "YYYYMMDD_NNN"
# 2.2 build.gradle — versionCode += 1 (ex.: 27 -> 28)
# 2.3 build.gradle — versionName (ex.: "1.464" -> "1.465")
```

Confirmar que a versão NOVA é a MESMA nos 3 lugares.

Se esta for uma release somente do APK, confirmar que `NativeDependencyManager.kt`
e o contrato dos ZIPs não foram alterados. Nesse caso, os pacotes existentes no
R2 serão reutilizados; não gerar ZIPs locais.

## 3. Build (gate completo)

```bash
cd "D:/Projetos/SIG"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

- **Critério de sucesso**: `BUILD SUCCESSFUL` no final. NÃO é sucesso se houver qualquer `FAIL`/`e:` (erro de compilação Kotlin aparece como `e: file:///...kt:NNN`).
- ⚠️ **Nunca encadear** `cp`/`commit` com um pipeline que engole o exit code (`... | grep | head` retorna 0 mesmo com build quebrado). Checar `BUILD SUCCESSFUL` na saída ANTES de prosseguir.
- APK gerado: `app/build/outputs/apk/debug/app-debug.apk`.
- Este fluxo é de release somente do APK. A geração e a aceitação dos ZIPs
  nativos só entram se a etapa 2 detectar mudança em `NativeDependencyManager.kt`
  ou no contrato do pacote nativo.

## 4. APK no O:\ (opcional — falha pode ser ignorada)

```bash
cd "D:/Projetos/SIG"
ls /o/ 2>/dev/null | head -1   # conferir se a unidade está montada
cp app/build/outputs/apk/debug/app-debug.apk "/o/sig.apk" && echo "APK copiado"
```

- **Mesmo nome sempre** (`sig.apk`).
- Se o `cp` falhar (unidade desmontada): **ignorar** e seguir para o commit — o destino importante é o GitHub.

## 5. Commit e push (na main)

```bash
cd "D:/Projetos/SIG"
git add -A
git commit -m "Versao YYYYMMDD_NNN: <descrição curta>"
git push origin main
```

- Não commitar: `local.properties`, chaves, `.gradle/`, `build/` (verificar `git status` antes do add se necessário).

## 6. GitHub Releases (asset `sig.apk` — SEMPRE renomear)

```bash
cd "D:/Projetos/SIG/app/build/outputs/apk/debug"
cp app-debug.apk sig.apk    # o nome do arquivo local vira o nome do asset
gh release create YYYYMMDD_NNN sig.apk \
  --repo spigknot/SIG-Android --title "SIG Android YYYYMMDD_NNN" --notes "<descrição>"
```

- **O nome do asset é o nome do arquivo local**: subir `app-debug.apk` cria um asset `app-debug.apk` (errado). SEMPRE renomear para `sig.apk` antes.
- Se a release ficar em draft (upload interrompido): `gh release edit YYYYMMDD_NNN --repo spigknot/SIG-Android --draft=false`.
- **Regra "só a versão atual"**: deletar a release anterior:

```bash
gh release delete <VERSAO_ANTERIOR> --repo spigknot/SIG-Android --yes
```

- ⚠️ Cadeias com `&&` cortam no primeiro passo que falha (ex.: `gh release delete` de tag inexistente impede o `create`). Separar os passos ou usar `;`.

## 7. Verificação final (antes de declarar pronto)

1. `gh release list --repo spigknot/SIG-Android` → só a versão nova, marcada Latest.
2. `gh release view YYYYMMDD_NNN --repo spigknot/SIG-Android --json assets` → asset `sig.apk` presente.
3. **SHA-256 do asset publicado == build local** (obrigatório):

```bash
python -c "
import urllib.request, hashlib
url = 'https://github.com/spigknot/SIG-Android/releases/download/YYYYMMDD_NNN/sig.apk'
remote = hashlib.sha256(urllib.request.urlopen(url).read()).hexdigest()
local = hashlib.sha256(open('app/build/outputs/apk/debug/app-debug.apk','rb').read()).hexdigest()
print('asset :', remote); print('local :', local); print('OK' if remote == local else 'DIVERGE!')
"
```

4. `git status` limpo (push feito).
5. O `APP_VERSION` do APK novo == a tag da release (senão o update checker se ofereceria a própria versão).

## 8. Entrega (relatório final obrigatório)

Ao concluir, reportar APENAS valores reais das saídas dos comandos:

1. A versão publicada (`YYYYMMDD_NNN`).
2. O resultado do build (`BUILD SUCCESSFUL` + testes/lint OK).
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
| Build "passou" mas commit saiu quebrado | pipeline `... | grep | head` engole o exit code | checar `BUILD SUCCESSFUL` na saída ANTES do cp/commit (seção 3) |
| App se oferece a própria versão como atualização | `APP_VERSION` do `AppUpdateChecker.kt` não foi bumpado | bump nos 3 lugares (seção 2) |
| Ferramentas ffmpeg falham no aparelho | pacote nativo ausente/corrompido; ou `smart-exception-java` removido do build.gradle (NoClassDefFoundError em runtime, build passa) | manter `com.arthenica:smart-exception-java:0.2.1`; ver logcat `SigNative`; o download nativo vem do R2 na 1ª exec |
| `AccessDenied` no bucket R2 `sig-android` | credencial de outro projeto ou escopo incorreto | usar a chave dedicada ao bucket `sig-android`; as credenciais do SIG Windows e do SIG Android são diferentes |
| Upload aparece no bucket errado | configuração compartilhada trouxe `bucket`/`public_base` de outro projeto | usar somente as credenciais S3 e forçar `bucket = sig-android` e a URL `pub-6476622beda24c82875cb84f11f660ea.r2.dev` |
| `RequestTimeTooSkewed` no R2 | relógio do Windows dessincronizado (w32time parado; >15 min de diferença) | `powershell -c "Start-Service w32time; w32tm /resync"` (elevação); conferir `date -u` vs `curl -sI https://api.cloudflare.com \| grep -i ^date:` |
| ZIPs de dependências locais ausentes ou com SHA diferente | a release é somente do APK, ou há reconstruções locais; os ZIPs nativos são versionados separadamente | em release somente do APK, reutilizar os ZIPs já publicados no R2; se o pacote nativo mudou, gerar e aceitar os arquivos que batem com `NativeDependencyManager.kt` antes de publicar |
| `O:\` desmontada no cp | unidade de rede indisponível | ignorar (destino menos importante); seguir com commit/GitHub |
| Dependência nativa baixando do Drive | `NativeDependencyManager.kt` com URL antiga | usar `https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/sig-android-dependencies-v1-<abi>.zip` |

---

## Contexto da transição (histórico)

- `20260817-002`: última versão com numeração antiga (`YYYYMMDD-NNN` com hífen) e dependências no Google Drive.
- `20260823_001`: primeira com a numeração do Windows (`YYYYMMDD_NNN`), dependências nativas no Cloudflare R2 (bucket `sig-android`), verificação de atualização via GitHub (`AppUpdateChecker`), e publicação "commit + push + APK no GitHub + O:\" como regra permanente.
- O Drive mantém os ZIPs de dependências por um tempo (APKs antigos ainda apontam para lá); quando não houver mais APKs antigos em campo, os arquivos do Drive podem ser removidos.
- Um APK antigo (sem o update checker) só atualiza manualmente; um APK novo (com o checker) avisa na abertura quando `releases/latest` for maior que o `APP_VERSION` dele.
