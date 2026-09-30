# Design — tela PROMPTS (Configurações) + sincronização com o R2

Data: 29/09/2026 · Projeto: SIG Android (`app/`) · Status: implementado; gates verdes

## 1. Problema e decisão

O app já lê os prompts de histórico e oitiva de arquivos
(`PromptTemplateStore` → `SIG/Prompts/*.txt` no aparelho, com fallback nos
assets), mas o usuário não tinha controle sobre eles: não dava para trocar um
prompt pelo texto de um arquivo `.txt`, nem atualizar o padrão sem instalar uma
versão nova do APK.

Esta mudança cria a tela **PROMPTS** (Configurações, entre `API KEYS` e
`AVANÇADO`) e torna **`D:\Projetos\SIG Windows\prompts` a fonte do conteúdo
padrão**, distribuída por dois canais:

| Canal | Papel |
|---|---|
| `app/src/main/assets/prompts/` | conteúdo embutido no APK (primeira execução / fallback) |
| bucket R2 `prompts` (`https://pub-916eee09ee6c4c20ad6a51523e965071.r2.dev`) | atualização do padrão sem nova versão do app |

O bucket é preenchido a partir da pasta do SIG Windows por
`D:\Projetos\SIG Windows\scripts\sync_prompts_r2.py` (fonte única).

## 2. Escopos (decisão do dono, 29/09/2026)

- **Upload para o bucket**: TODOS os `.txt` de `prompts/`, incluindo
  `prompts_antigos/` — backup dos prompts antigos e fonte para o **SIG
  Windows**, que vai consumir os demais prompts de lá.
- **Download no app (botão “baixar prompts atualizados”)**: SOMENTE os 4
  prompts de histórico/oitiva. A subpasta e os demais prompts são ignorados.
- **Tela**: gerencia somente os 4 slots abaixo (partes e qualificação seguem
  como estão, fora deste escopo).

## 3. Slots

| Slot (`PromptSlot`) | Arquivo | Marcador exigido no download |
|---|---|---|
| `HISTORY_SYSTEM` | `historico_system.txt` | — |
| `HISTORY_USER` | `historico_user.txt` | `{{conteudo_caixa_transcricao}}` |
| `STATEMENT_SYSTEM` | `oitiva_system.txt` | — |
| `STATEMENT_USER` | `oitiva_user.txt` | `{{{conteudo_caixa_historico}}}` (ou o legado) |

O marcador é exigido porque o app o substitui a cada requisição: um prompt
baixado sem marcador quebraria o fluxo em silêncio (o texto chegaria com o
marcador cru na resposta).

## 4. Modelo de dados (área E/C)

```
<SIG>/Prompts/
  padrao/<os 4 arquivos>      padrão do app (seed dos assets; trocado pelo R2)
  custom/<slot>/<id>.txt      prompts do usuário (id = nome do arquivo)
  ativo.properties            historico_system=padrao | <id>, ... (em uso)
  LEIA-ME.txt
  partes_*.txt, qualificacao_*.txt   (legado — fora deste escopo, inalterados)
```

- **`Padrão`** (id `padrao`) é **intocável pelo menu de configurações**: o
  botão `SALVAR` fica desabilitado para ele e a única via de mudança do
  conteúdo padrão é o botão de atualizar (R2). **+ → SALVAR → nome** grava
  um `<id>` novo sem tocar no padrão.
- **A escolha do usuário é persistente**: fica em `ativo.properties` e vale
  na próxima vez que o app abrir.
- **Download do R2** grava só em `padrao/` (validação all-or-nothing) — os
  customizados são preservados por construção.
- **Migração** (primeira execução da versão nova):
  - arquivo da raiz igual ao asset → vira `padrao` (é o padrão);
  - arquivo da raiz diferente → vira `custom/<slot>/<id>` e fica **ativo**
    (preserva a edição que o usuário já tinha);
  - sem arquivos na raiz → tudo `padrao`.
- **Leitura**: `readActive(slot)` = `ativo.properties` → `custom/…`; se faltar ou
  estiver em branco, cai para `padrao/`, depois para o asset.

## 5. Tela (área A — `PromptsSettingsActivity`)

Reorganização vigente: duas abas reais **Histórico**, depois **Oitiva**.
Cada aba contém duas seções, **System**, depois **User**, visíveis ao mesmo
tempo e com estado próprio (lista, seleção, texto salvo e rascunho).

- Em cada seção, a linha do título tem **System/User à esquerda** e **+ na
  borda direita da área de conteúdo**, separado dos rádios. O ImageButton é
  idêntico ao `button_select_media` de `activity_remote_stt_transcription.xml`:
  `ffmpeg_outline_button_bg`, `ic_ffmpeg_select_file`, foreground selecionável,
  largura 44dp, padding 9dp, fitCenter; só a altura é adaptada para 36dp.
- A grade tem três opções por linha: **Padrão** primeiro,
  customizados/importados depois. Nomes longos continuam
  truncados em dez caracteres com `...`; o nome completo está na acessibilidade.
- Selecionar um rádio persiste o slot em `ativo.properties` e atualiza somente
  seu editor imediatamente. Se houver rascunho não salvo, **Cancelar** não muda
  nem o arquivo ativo nem a seleção; **Descartar** confirma antes de persistir.
- **+** abre editor editável com título exatamente `Histórico (system)`,
  `Histórico (user)`, `Oitiva (system)` ou `Oitiva (user)`. O texto inicial é
  sempre o **Padrão daquele slot**, mesmo se um customizado estiver ativo.
  **SALVAR** pede **Nome do prompt**; nome vazio, reservado ou duplicado não
  fecha o diálogo. Ao salvar, o novo rádio entra na lista da seção e fica ativo.
- Cada seção tem seu próprio editor e **uma linha** com os rótulos exatos
  **Salvar**, **Importar**, **Deletar**. Não existe Salvar como nem atualização por seção.
- **Padrão** é protegido: editor somente leitura, Salvar/Deletar desabilitados,
  proteção adicional no núcleo. Deletar um customizado exige confirmação e
  volta ao Padrão se estava ativo.
- **Importar** captura o slot do botão que abriu o seletor (inclusive recriação
  da Activity); não depende da última seção tocada. Arquivo de outro slot pede
  confirmação antes de importar. Importação não modifica a outra seção.
- **Atualizar prompts** é um único ImageButton sem texto no cabeçalho global,
  na mesma linha de Voltar e alinhado à margem direita da área de conteúdo.
  Usa o vector original `ic_prompts_refresh`: setas orbitais e brilho central
  em traços verdes (`#FF7CD98A`), alvo 48dp e contentDescription
  `Atualizar prompts`. Aciona a função global existente `updateFromR2()`.
  Consulta somente os **quatro arquivos** permitidos do R2,
  aplica o conjunto validado all-or-nothing e recarrega somente os editores de
  Padrão cujos arquivos mudaram; customizados, seleção e rascunhos permanecem.
- Alternar abas não recria editores nem descarta rascunhos. Recriação da tela
  guarda aba, quatro rascunhos e slot de importação no Bundle.

## 6. Download do R2

- Constantes em `PromptStoreCore`: `R2_PROMPTS_BASE_URL` + as 4 chaves.
- Validação (mesma regra do script de upload): não vazio, ≤ 64 KiB, UTF-8
  válido, marcador presente. **Nada é gravado se algum dos 4 reprovar**
  (all-or-nothing) — um download parcial deixaria o padrão inconsistente.
- Antes de gravar, `changedDefaults()` compara o baixado com o `padrao/`
  atual: lista vazia = já atualizado, então o botão não regrava nada.
- Rede em thread de fundo (OkHttp), resultado na linha de mensagem da tela.

## 7. Vacinas (testes permanentes)

`PromptStoreCoreTest` cobre: seed do padrão a partir do
asset, migração dos dois casos da raiz, recusa de sobrescrita do `Padrão`,
criação por **+ → SALVAR → nome**, validação do download (marcador/vazio/tamanho/all-or-nothing),
preservação dos customizados após download, inferência de slot na importação e
fallback `custom` → `padrao` — o rótulo da opção (`Padrão`, ou o nome sem
extensão truncado em 10 caracteres + `"..."`, sem reticências até 10) — e
também a exclusão (recusa do `Padrão`, remoção do arquivo, volta ao `Padrão`
quando o excluído estava em uso e erro ao excluir duas vezes).

Além disso, `PromptRequestWiringTest` é a **prova da ligação menu → rede**:
com MockWebServer ele confere que o prompt marcado na tela chega como `system`
e como `user` no corpo real das duas requisições (histórico e oitiva), com os
marcadores preenchidos, e que voltar para `Padrão` muda o request.
`PromptAssetsParityTest` confere que os 4 prompts embutidos no APK são byte a
byte os de `D:\Projetos\SIG Windows\prompts`.

`PromptsScreenContractTest` trava duas abas reais na ordem Histórico/Oitiva,
containers independentes, remoção do seletor de tipo e de Salvar como, labels
sem transformação em maiúsculas, e paridade de background/foreground/padding/
src/scaleType/largura do **+** com o botão real da Transcrição. O teste do
cabeçalho passou por RED (ausência do layout) → GREEN. `PromptStoreCoreTest`
também cobre criar a partir do Padrão mesmo com custom ativo, persistir os
quatro slots após reabrir e deletar um slot sem modificar os outros três.

Verificação desta reorganização: **527 testes, 0 falhas/erros/skips**;
`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` verdes juntos.
APK instalado com `adb -s emulator-5554 install -r`, sem apagar dados.
DOM do uiautomator confirmou títulos exatos dos quatro diálogos, editores
editáveis, quatro linhas de ações e **+** alinhados à direita dos cabeçalhos
(x final 1053 no viewport 1080; fora das listas de rádios). Criação com nome,
rádio novo ativo, reabertura persistente e exclusão confirmada foram exercidas
com um custom temporário próprio, removido ao final; custom existentes intactos.
Atualização R2 real mudou os quatro padrões, preservando hashes dos custom e
`ativo.properties`; `AndroidRuntime:E` vazio na janela de verificação.
Evidências locais (não versionadas):
`C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/prompts-final-*.xml`,
`prompts-position-evidence.json` e `prompts-final-gates.log`.

Gates do repo antes de aceitar: `:app:testDebugUnitTest`, `:app:lintDebug`,
`:app:assembleDebug` e `scripts/check-module-map.ps1` (linhas novas no mapa).

## 8. Ajuste de altura e status estável (30/09/2026)

- Os quatro editores das abas passam de **180dp para 239.4dp**, cálculo decimal
  `180 × 1.33 = 239.40`: aumento exatamente de **33%**, não um terço.
  A dimensão XML `prompts_dimensions.xml` é aplicada somente em `buildSection`;
  o diálogo de criação **+** permanece com a altura anterior (`WRAP_CONTENT`).
  O Android arredonda a medida para pixels conforme a densidade do aparelho.
- `prompt_message` sai do fluxo vertical acima das abas e passa a uma área de
  rodapé **reservada de 56dp**, acima dos 64dp do footer existente. O conteúdo
  tem padding inferior de 120dp: não há sobreposição permanente de ações/editor.
  A área não muda de altura quando a mensagem aparece/desaparece; mensagens
  extensas são roláveis e o TextView tem `accessibilityLiveRegion="polite"`.
  Os caminhos existentes de progresso, erro e sucesso continuam usando `showMessage`.
- Duas vacinas XML/código em `PromptsScreenContractTest` travam o fator decimal,
  aplicação aos quatro slots, preservação do +, parent independente das abas,
  reserva de espaço, altura fixa, rolagem e live region.
- Gates reais juntos: **530 testes, 0 falhas/erros/skips**, lint e APK verdes.
  Log local: `C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/prompts-height-gates.log`.
- APK instalado via `install -r` em **emulator-5554**, dados preservados.
  DOM confirmou **533px** em cada um dos quatro editores (densidade 356dpi).
  Histórico System `[27,595][1053,1128]`, User `[27,1428][1053,1961]`;
  Oitiva System `[27,524][1053,1057]`, User `[27,1357][1053,1890]`.
  Antes/durante/depois da consulta R2, todos os bounds de header, abas, títulos,
  editores e ações do Histórico ficaram **idênticos**. Progresso capturado:
  `Consultando o R2…`; sucesso: `Seus prompts já estão atualizados.` no rodapé
  `[27,2050][1053,2175]`. Rede temporariamente limitada no emulador para capturar
  progresso e restaurada para `full` ao concluir.
- SHA-256 de **11 arquivos** (quatro padrões, seis custom e `ativo.properties`)
  idênticos antes/depois da atualização real; nenhum padrão regravado.
  Evidência local: `prompts-height-position-evidence.json`, dumps
  `prompts-height-before.xml`, `prompts-height-during.xml`,
  `prompts-height-after.xml`, `prompts-height-oitiva.xml` no mesmo scratch.
  PROMPTS deixado aberto na aba Histórico. `AndroidRuntime:E` sem saída.
  Sem commit/push, sem alterações em credenciais ou no SIG Windows.

### Ajuste final — System +50%, User −50% (30/09/2026)

- Base anterior **239.4dp**: System **359.1dp** (`×1.5`), User **119.7dp** (`×0.5`), nas duas abas. `buildSection` seleciona a dimensão por `slot.isSystem`; o diálogo **+** permanece inalterado.
- Gates reaproveitados de `prompts-split-gates.log`: `testDebugUnitTest`, `lintDebug` e `assembleDebug`, **BUILD SUCCESSFUL**; resultados XML: **530 testes, zero falhas/erros/skips**.
- APK reinstalado com `adb -s emulator-5554 install -r`: **Success**, dados preservados. Navegação real Main → Ferramentas → Ocorrência → Configurações → PROMPTS, sem exportar Activities.
- DOM com densidade efetiva **356dpi** confirmou **799px System / 266px User** em ambas as abas, sem clipping dos editores: Histórico `[27,595][1053,1394]` / `[27,1694][1053,1960]`; Oitiva `[27,524][1053,1323]` / `[27,1623][1053,1889]`.
- Evidências no scratch: `prompts-split-history-top.xml`, `prompts-split-oitiva-top.xml`, `prompts-split-delivery-evidence.json`. PROMPTS permanece aberto. Nenhuma operação R2, edição de customizados, commit ou push nesta verificação.

## 9. Fases

1. ✅ Credenciais do bucket `prompts` registradas fora do Git +
   `scripts/sync_prompts_r2.py` + upload dos 18 `.txt` (verificado por sha256
   no URL público).
2. ✅ Núcleo (`PromptStoreCore`) + testes.
3. ✅ Tela + botão no Configurações + manifesto + MODULE-MAP.
4. ✅ Gates: `testDebugUnitTest` (523 testes, 0 falhas), `lintDebug`,
   `assembleDebug` e `check-module-map.ps1 -Quiet` — todos verdes.

## 10. Fora de escopo (deliberado)

- Geração de histórico/oitiva do **SIG Windows** a partir do bucket — o upload
  dos demais prompts já habilita, mas o consumo fica para quando o SIG Windows
  for alterado.
- Prompts de partes e qualificação na tela (permanecem como estão).
