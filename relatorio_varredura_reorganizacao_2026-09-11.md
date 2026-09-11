# Varredura de referências — pós-reorganização (11/09/2026)

Escopo: `D:\Projetos\SIG` (módulo `:app` + `tools/` + `scripts/`).
Alvo: coisas **usadas mas não importadas/definidas**, coisas **importadas mas não
usadas** e resíduos da reorganização (código morto, ids/recursos órfãos,
referências por string). Nada foi alterado — este relatório só aponta.

## Método (4 frentes independentes)

| Frente | Como | O que cobre |
|---|---|---|
| Compilação real | `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks` | símbolo usado e não importado (erro de compilação), `R.*` inexistente |
| Análise estática própria | `python tools/audit/scan_kotlin.py`, `scan_cross.py`, `scan_xml.py`, `scan_ps.py` | imports não usados, código morto, id de outro layout, seams órfãs, recursos |
| Gate do projeto | `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `scripts/check-module-map.ps1`, `scripts/tests/validate-agent-harness.tests.ps1 -Quiet` | comportamento, contrato do harness/MODULE-MAP |
| ferramentas do lab | `python -m pyflakes` em todos os `.py` versionados (fora do `whisper.cpp`) | nome indefinido, import não usado em Python |

## Resumo

| Severidade | Achado | Qtd |
|---|---|---|
| **Bug real** | `tools/granite/nar/run_reference.py:51` — `NameError` confirmado em execução | 1 |
| Higiene | imports não usados em Kotlin (main + testes) | **43** |
| Higiene | declarações sem nenhuma referência no repo (código morto) | 8 |
| Higiene | `android:id` declarado e nunca referenciado | 12 |
| Higiene | recurso definido e sem referência (`style/SettingsGroup`) | 1 |
| Higiene | achados do pyflakes nos `.py` do lab (imports/variáveis/f-strings) | 43 (~40 de higiene) |
| OK | usado-e-não-importado em Kotlin / recurso inexistente / id de outro layout / classe por string / tela órfã | **0** |

---

## 1. Usado mas não importado / não definido

### Kotlin (`:app`) — NADA
`compileDebugKotlin` + `compileDebugUnitTestKotlin` (`--rerun-tasks`): **BUILD
SUCCESSFUL, 0 erros**, 21 tasks executadas, 38 warnings — todos depreciações
(`startActivityForResult`, `scaledDensity`, `statusBarColor`) sem relação com a
reorganização. Em Kotlin, símbolo usado sem import não compila: a ausência de
erros é prova de que não existe esse caso no módulo.

### Python (lab) — 1 BUG REAL
`tools/granite/nar/run_reference.py:51`

```python
def build_slots(ctc_tokens: list[int]) -> list[int]:
    """Delegado: a regra (blank intercalado) mora em `common.build_insertion_slots`."""
    return common.build_insertion_slots(ctc_tokens, BLANK, MIN_EDIT)   # <-- common não está importado
```

O arquivo faz `from common import (...)` (importa **nomes**, não o módulo) e
`build_slots` é chamado na linha 121. Prova em execução:

```
$ python -c "import sys; sys.path.insert(0,'tools/granite/nar'); import run_reference as rr; rr.build_slots([1,2,3])"
NameError: name 'common' is not defined
```

Falso positivo verificado (NÃO é bug): `mask_gate.py:97/105` — o pyflakes diz
`undefined name 'lm'`, mas `lm = model.language_model` (linha 80) é variável
livre válida dos closures (`symtable`: `is_free() == True`); o aviso vem do
`del model, lm` da linha 158.

### Referências que escapam do compilador — todas OK
- **Classe por string** (`Class.forName`/`setClassName`): só as 3 do
  `FfmpegKitClasspathTest` (intencionais — é a vacina do smart-exception).
- **Activities ↔ AndroidManifest**: 100% pareadas (nenhuma entrada apontando
  para classe ausente e nenhuma tela sem entrada no manifest).
- **Recurso XML inexistente** (`@string/@drawable/@layout/R.*`): 0.
- **Views customizadas usadas por FQN no XML** (`FfmpegRangeSlider`,
  `FfmpegWaveformView`, `FfmpegJoinTimelineView`,
  `FfmpegJoinPlaybackTimelineView`, `FfmpegInsertAudioTimelineView`,
  `AppVersionTextView`): todas existem.
- **`android:onClick`**: não é usado em nenhum layout (nada a resolver por string).
- **Símbolos do pacote NPU removido em `719842c`** (`NpuTestActivity`,
  `ic_tool_npu`, `npu_model_manifest`, ...): só citados em
  `docs/relatorio-qnn-gpu-npu-20260829.txt` (relatório histórico datado, sem efeito).
- **PowerShell/CI**: nenhum dot-source quebrado, nenhuma função do projeto
  chamada e não definida (o `Redact-Diagnostic` do `validate-agent-harness.ps1`
  vem de `scripts/lib/diagnostics.ps1`, dot-sourced na linha 61).

---

## 2. Importado e não usado

### Kotlin — 43 (todos conferidos: o identificador aparece **zero** vezes fora da linha de import)

| Arquivo | Imports não usados |
|---|---|
| `FfmpegCutActivity.kt` (11) | `android.os.Environment`, `android.provider.MediaStore`, `android.provider.OpenableColumns`, `android.provider.Settings`, `android.text.SpannableString`, `android.text.Spanned`, `android.text.method.LinkMovementMethod`, `android.text.style.ClickableSpan`, `androidx.core.content.FileProvider`, `java.text.SimpleDateFormat`, `java.util.Date` |
| `GraniteActivity.kt` (7) | `android.text.SpannableString`, `android.text.Spanned`, `android.text.style.ForegroundColorSpan`, `android.widget.FrameLayout`, `android.widget.ImageView`, `java.io.RandomAccessFile`, `kotlin.math.roundToLong` |
| `FfmpegCleanAudioActivity.kt` (4) | `android.os.Environment`, `androidx.core.content.FileProvider`, `java.text.SimpleDateFormat`, `java.util.Date` |
| `FfmpegJoinVideosActivity.kt` (4) | `android.provider.OpenableColumns`, `android.widget.Button`, `android.widget.LinearLayout`, `androidx.core.content.FileProvider` |
| `GraniteNarEngine.kt` (4) | `kotlin.math.PI`, `kotlin.math.cos`, `kotlin.math.floor`, `kotlin.math.sin` |
| `FfmpegExtractAudioActivity.kt` (3) | `android.provider.OpenableColumns`, `java.text.SimpleDateFormat`, `java.util.Date` |
| `FfmpegRotateVideoActivity.kt` (2) | `android.provider.OpenableColumns`, `android.provider.Settings` |
| `GraniteEngine.kt` (2) | `java.io.RandomAccessFile`, `kotlin.math.pow` |
| `WhisperActivity.kt` (2) | `android.provider.OpenableColumns`, `android.provider.Settings` |
| `FfmpegInsertAudioActivity.kt` (1) | `android.provider.OpenableColumns` |
| `RemoteSttActivity.kt` (1) | `android.text.SpannableStringBuilder` |
| `test/GraniteBinarySupportTest.kt` (1) | `java.io.FileOutputStream` |
| `test/GraniteEngineTest.kt` (1) | `kotlin.math.cos` |

Padrão claro (resíduo da extração dos seams): `OpenableColumns`/`Settings`/
`Environment`/`FileProvider`/`SimpleDateFormat`/`Date` foram para
`MediaUriSupport`/`MediaTypeRules`/`TranscriptionReport`; `RandomAccessFile`/
`byte↔codepoint` para `LittleEndianIo`/`GraniteBinarySupport`;
`SpannableString*` para `SttResponseParsers`. Não há wildcard import nem import
duplicado em nenhum arquivo.

### Python (lab) — 29 imports não usados
Lista completa em `python -m pyflakes $(git ls-files "*.py" | grep -v app/src/main/cpp)`.
Concentrados em `tools/granite/*.py` e `tools/granite/nar/*.py`
(`json`, `sys`, `numpy as np`, `time`, `math`, `shutil`, `random`, e nomes
importados de `common`: `sha256_file`, `sha256_bytes`, `step_status`).

---

## 3. Código morto (declarado e nunca referenciado no repositório)

Conferido por `git grep` (1 ocorrência = só a própria declaração):

| Símbolo | Onde | Observação |
|---|---|---|
| `SaveResult` (`private data class`) ×3 | `FfmpegCutActivity.kt:2191`, `FfmpegExtractAudioActivity.kt:1791`, `FfmpegRotateVideoActivity.kt:2320` | resto de antes da extração; 3 cópias |
| `serverNameForIp` (`private fun`) | `RemoteSttActivity.kt:888` | função privada sem chamador |
| `requestHistoryAndNames` | `TranscriptAssistantClient.kt:48` | a irmã `requestHistory` é a usada (RemoteSttActivity + teste) |
| `partsUserPromptFromTranscription` | `PromptTemplateStore.kt:51` | a irmã `partsUserPromptFromHistory` é a usada |
| `removeName` | `NameDatabaseStore.kt:70` | `addName` é usado; remoção de nome nunca é chamada |
| `modelDataFile` | `GraniteEngine.kt:468` | wrapper morto |
| `modelDataFileFp16` | `GraniteEngine.kt:472` | wrapper morto |
| `isDownloaded` | `GraniteEngine.kt:478` | wrapper morto (`= packageComplete(context)`) |

**Funções públicas das seams sem consumidor de produção** (usadas só dentro da
própria seam e/ou pelo teste — candidatas a `internal`/`private`):
`SttResponseParsers.formatTimedEntry`, `formatTimestamp`, `extractTextDelta`,
`isServerEnvelopeLine`; `SttAudioProbe.parseLogs`, `metadataSummary`.
As duas últimas da lista (`extractTextDelta`, `isServerEnvelopeLine`) são código
morto **intencional** — preservado e travado por teste conforme
`docs/refatoracao-legibilidade-20260908.md` §"comportamentos estranhos preservados".

---

## 4. Ids e recursos de XML órfãos

**12 `android:id` declarados e nunca referenciados** (nem por `R.id.`, nem por
`@id/`, nem por `tools:`):

| Layout | Ids |
|---|---|
| `activity_model_settings.xml` | `history_models_empty`, `statement_models_empty`, `transcription_models_empty`, `label_parts_model` |
| `activity_ffmpeg_join_videos.xml` | `join_media_area`, `result_playback_controls` |
| `activity_main.xml` | `bgLogo`, `root` |
| `activity_ffmpeg_cut.xml` | `cut_title` |
| `activity_ffmpeg.xml` | `ffmpeg_title` |
| `activity_remote_stt_occurrence.xml` | `live_interval_controls` |
| `view_sig_echo_footer.xml` | `text_app_version` (a classe `AppVersionTextView` seta o texto sozinha) |

Atenção a 3 deles, que são **containers** com filhos usados no código:
`result_playback_controls` (filhos `result_play_pause`/`result_speed_down`/
`result_speed_up` são usados em `FfmpegJoinVideosActivity:174-176`),
`live_interval_controls` (filhos `button_live_interval_*`/`input_live_interval`
usados no `RemoteSttActivity`) e `join_media_area`. Se a intenção original era
esconder/mostrar o grupo inteiro, hoje o código manipula os filhos um a um —
vale decidir: usar o container ou remover o id.

**1 recurso definido e sem referência**: `style/SettingsGroup`
(`res/values/settings_styles.xml:12`) — o lint aponta o mesmo.
(`style/Theme.SIG.Base` **não** é órfão: é `parent` de `Theme.SIG`.)

Nenhum layout/drawable/menu/anim sem referência; nenhum id duplicado dentro do
mesmo arquivo.

---

## 5. Verificações que passaram (evidência negativa)

| Verificação | Resultado |
|---|---|
| `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin --rerun-tasks` | BUILD SUCCESSFUL, 0 erros |
| `:app:testDebugUnitTest` | 32 classes / **358 testes**, 0 falhas, 0 erros, 0 ignorados |
| `:app:lintDebug` | 1029 avisos, **0 erros** (HardcodedText/SetTextI18n/UseKtx dominam) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `scripts/check-module-map.ps1 -Quiet` | exit 0 (todo fonte de produção tem linha no MODULE-MAP e KDoc) |
| `scripts/tests/validate-agent-harness.tests.ps1 -Quiet` | exit 0 e silencioso (bootstrap, EvidencePath, staged snapshot, contrato do MODULE-MAP, hooks, pre-commit) |
| `R.id` de layout diferente do arquivo (bug classe do NPE do `setProcessing`) | 0 |
| `android:onClick` / view customizada inexistente | 0 |
| Dependências do `app/build.gradle` sem uso | 0 (`material` entra via `Theme.MaterialComponents.DayNight.NoActionBar`) |

**Dívida de duplicação documentada e ainda presente** (não é regressão; é a lista
de "divergências reais não unificadas" de `docs/refatoracao-legibilidade-20260908.md`):
`formatTime` 11 arquivos, `dp` 9, `copyUriToCache` 9, `setProcessing` 8,
`clearOutputResult`/`openOutputFile`/`openOutputFolder` 8, `releasePreviewPlayer`
5, `releaseAudioPlayer` 3, `formatSeconds`/`parseTime` 5, etc.

---

## 6. Como reproduzir

```bash
# análise estática própria (nenhuma escrita, só stdout)
python tools/audit/scan_kotlin.py     # imports não usados, id de outro layout, classe por string
python tools/audit/scan_cross.py      # seams, código morto, recursos vs referências
python tools/audit/scan_xml.py        # ids/recursos declarados sem uso
python tools/audit/scan_ps.py         # PowerShell e workflows

# Python do lab
git ls-files "*.py" | grep -v app/src/main/cpp | xargs python -m pyflakes

# gates do projeto
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
powershell -File scripts/check-module-map.ps1 -Quiet
powershell -File scripts/tests/validate-agent-harness.tests.ps1 -Quiet
```

## 7. Recomendações (em ordem de custo/benefício)

1. **Corrigir `run_reference.py`** (`import common` ou importar
   `build_insertion_slots` diretamente) — é o único defeito funcional achado.
2. **Remover os 43 imports não usados** (mecânico, risco zero) para não repetir o
   pitfall "patch de import engole vizinho" à toa.
3. **Decidir o destino do código morto** (8 símbolos): os 3 `SaveResult` e
   `serverNameForIp` são restos claros; `requestHistoryAndNames`,
   `partsUserPromptFromTranscription` e `removeName` podem ser API guardada de
   propósito — se for, marcar com KDoc (senão vão parecer bug para o próximo).
4. **Ids órfãos**: remover os triviais (`cut_title`, `ffmpeg_title`, `bgLogo`,
   `root`, `text_app_version`, os 4 de `activity_model_settings.xml`); os 3
   containers merecem decisão (usar para show/hide ou remover o id).
5. `style/SettingsGroup`: usar ou remover.
6. Higiene do lab Python (29 imports, 7 variáveis locais, 3 f-strings sem
   placeholder) quando for mexer nos scripts.
