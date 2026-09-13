# Especificação — Seleção de Encoder (GPU/CPU + Avançado) e Modos de Corte

**Origem (verdade):** `D:\Projetos\SIG Windows\src\video_encoders.py` (185 linhas, lido integralmente) e `D:\Projetos\SIG Windows\src\ffmpeg_tools_panel.py` (7022 linhas; funções citadas abaixo com números de linha do estado lido em 2026-09-12).
**Testes que travam as regras:** `tests/test_video_encoders.py`, `tests/test_ffmpeg_cut_modes.py`.
**Objetivo:** permitir reimplementar fielmente no SIG Android (Kotlin). A LÓGICA (catálogo, árvore de decisão, motivos, fallback, modos de corte, hvc1) é a mesma; no Android mudam apenas os nomes concretos de encoder (ex.: `mediacodec`/`libx264` em vez de `h264_nvenc`/`libx264`) e a sondagem (lista real de encoders do SO em vez de `ffmpeg -encoders`).
Strings exatas do código estão entre crases; onde o código não determina algo, está escrito **NÃO DETERMINADO no código** com o ponto onde se procurou.

---

## 1. Catálogo (`video_encoders.py`)

### 1.1 Constantes

| Nome | Valor exato | Uso |
|---|---|---|
| `ENCODER_PATH_GPU` | `"gpu"` | caminho "onde processar" = GPU |
| `ENCODER_PATH_CPU` | `"cpu"` | caminho "onde processar" = CPU |
| `ENCODER_ADVANCED_AUTO` | `"auto"` | valor do Avançado = Automático |
| `PATH_LABELS` | `{ENCODER_PATH_GPU: "GPU", ENCODER_PATH_CPU: "CPU"}` | rótulos exibidos no combo principal |
| `SHORT_JOB_SECONDS` | `3.0` | limiar de "trecho curto" (ver 2) |

Comentário do código para `SHORT_JOB_SECONDS` (texto exato): `"Trecho curto: abaixo disto a inicializacao da GPU (~0,4-0,6s medidos nesta maquina) nao compensa — o software entrega o mesmo tempo e arquivo menor."`

### 1.2 `EncoderOption` (dataclass `frozen`)

Campos, nesta ordem: `key: str`, `label: str`, `path: str`, `codec: str`, `encoder: str`, `priority: int`.
Semântica: `key` = família (ex. `"nvenc"`, `"cpu"`); `label` = rótulo de UI (ex. `"NVENC (NVIDIA)"`); `path` = `"gpu"`/`"cpu"`; `codec` = codec que o encoder produz (`"h264"`/`"hevc"`); `encoder` = nome do encoder no ffmpeg; `priority` = **menor é mais preferido** entre as GPUs.

### 1.3 `CATALOG` (tupla completa — 9 entradas, nesta ordem)

| # | key | label | path | codec | encoder | priority |
|---|---|---|---|---|---|---|
| 1 | `nvenc` | `NVENC (NVIDIA)` | `gpu` | `h264` | `h264_nvenc` | 10 |
| 2 | `nvenc` | `NVENC (NVIDIA)` | `gpu` | `hevc` | `hevc_nvenc` | 10 |
| 3 | `qsv` | `QSV (Intel)` | `gpu` | `h264` | `h264_qsv` | 20 |
| 4 | `qsv` | `QSV (Intel)` | `gpu` | `hevc` | `hevc_qsv` | 20 |
| 5 | `amf` | `AMF (AMD)` | `gpu` | `h264` | `h264_amf` | 30 |
| 6 | `amf` | `AMF (AMD)` | `gpu` | `hevc` | `hevc_amf` | 30 |
| 7 | `cpu` | `CPU` | `cpu` | `h264` | `libx264` | 100 |
| 8 | `cpu` | `CPU` | `cpu` | `hevc` | `libx265` | 100 |
| 9 | `cpu-mpeg4` | `CPU (compativel)` | `cpu` | `h264` | `mpeg4` | 200 |

Comentários do código: item 9 é `"Ultimo recurso: MPEG-4 Part 2 quando nao ha libx264 (compatibilidade)."`; o catálogo é `"só o que o ffmpeg empacotado realmente oferece hoje (h264/hevc), com os pares HEVC de cada familia (necessarios para preservar o codec da fonte)"`.
Cada família GPU (`nvenc`/`qsv`/`amf`) tem par h264 + hevc; CPU tem `libx264`/`libx265` + o último recurso `mpeg4`. Não existe entrada `vaapi` no `CATALOG` atual (o código de sondagem ainda contém guardas de `vaapi`, mas nenhuma opção usa essa key hoje — ver 3.2).

### 1.4 `CODEC_ALIASES` (aliases de codec)

| alias | normalizado |
|---|---|
| `h264` | `h264` |
| `avc` | `h264` |
| `avc1` | `h264` |
| `hevc` | `hevc` |
| `h265` | `hevc` |
| `hvc1` | `hevc` |
| `hev1` | `hevc` |

### 1.5 Funções utilitárias (semântica exata)

- `normalize_codec(codec: str) -> str`: consulta `CODEC_ALIASES` com `str(codec or "").lower()` (**sem trim** — `" h264"` com espaço não normaliza) e devolve `""` quando não há par (ex.: `"vp9"` → `""`). Note que `""`/`None` → `""`.
- `catalog_options(codec: str = "", path: str = "") -> list[EncoderOption]`: filtra `CATALOG` por `option.codec == normalize_codec(codec)` (só se `codec` não vazio) e `option.path == path` (só se `path` não vazio); devolve ordenado por `(option.priority, option.encoder)`. `codec` desconhecido normaliza para `""` → **sem filtro de codec** (cuidado no port). Ex. com `codec="h264", path="gpu"` → keys `["nvenc", "qsv", "amf"]`.
- `available_keys(available: list[EncoderOption]) -> list[str]`: percorre as opções **GPU** ordenadas por `priority` e devolve as `key` sem repetir, na ordem de preferência (ex.: `["nvenc","qsv"]`). Ignora opções de CPU. (Importada pelo painel, mas **não chamada em nenhum ponto do `ffmpeg_tools_panel.py`** — verificado por busca; mantida como utilitária/serialização.)
- `options_to_tuples(options) -> list[tuple]`: serializa cada opção como a tupla de 6 campos `(key, label, path, codec, encoder, priority)` para atravessar a fronteira da UI thread (o motivo declarado: `"Serializa o catalogo sondado para atravessar a fronteira da UI thread."`).
- `options_from_tuples(pairs) -> list[EncoderOption]`: aceita `None` → `[]`; para cada item tenta desempacotar 6 campos, converte tudo a `str` e `priority` a `int`; qualquer `TypeError`/`ValueError` → **item é silenciosamente pulado** (`continue`). Ex.: `[("x",)]` → `[]`.
- `hevc_tag_arguments` — ver seção 7.
- `EncoderChoice` (dataclass `frozen`): `option: EncoderOption`, `reason: str` — `"Encoder escolhido + por que (o motivo vai para o log)."`

Regra de ouro do módulo (docstring, texto exato): *"Nada de rebaixamento silencioso: fora do automatico um forcado indisponivel vira software COM aviso; falha de hardware em tempo de execucao repete a tarefa na CPU sem trocar a preferencia do usuario."*

---

## 2. Resolvedor — árvore de decisão COMPLETA (`resolve_encoder`)

Assinatura: `resolve_encoder(*, codec, path, available, advanced=ENCODER_ADVANCED_AUTO, seconds=0.0, short_job_seconds=SHORT_JOB_SECONDS) -> EncoderChoice | None` (todos os parâmetros são **keyword-only**).

Pseudocódigo fiel (cada retorno com o MOTIVO exato):

```
alvo = normalize_codec(codec) or "h264"          # codec vazio/desconhecido -> H.264
viaveis   = [op for op in available if op.codec == alvo]
software  = primeira opção de viaveis com path == "cpu"   (ou None)

(A) if path == "cpu":                            # usuário escolheu CPU
      if software is None: return None
      return EncoderChoice(software,
          `modo CPU escolhido pelo usuario ({software.encoder})`)
          # ex.: "modo CPU escolhido pelo usuario (libx264)"

hardware = [op de viaveis com path == "gpu"]

(B) if advanced not None/"" and advanced != "auto":        # forçado no Avançado
      forcado = primeira opção de hardware com op.key == advanced
      if forcado: return EncoderChoice(forcado,
          `{forcado.label} forcado no Avancado`)
          # ex.: "NVENC (NVIDIA) forcado no Avancado"
      if software is None: return None
      return EncoderChoice(software,
          `{advanced} nao passou na sondagem desta maquina; usando CPU ({software.encoder})`)
          # ex.: "nvenc nao passou na sondagem desta maquina; usando CPU (libx264)"

(C) if hardware is empty:                        # GPU sem o codec pedido
      if software is None: return None
      return EncoderChoice(software,
          `a GPU desta maquina nao tem encoder {alvo.upper()}; usando CPU ({software.encoder})`)
          # ex.: "a GPU desta maquina nao tem encoder HEVC; usando CPU (libx265)"

preferido = único item de hardware, ou o de MENOR priority entre eles

(D) if seconds (truthy, > 0) and seconds < short_job_seconds and software is not None:
      return EncoderChoice(software,
          `trecho curto de {seconds:.2f}s (< {short_job_seconds:g}s): a inicializacao da GPU nao compensa; usando CPU ({software.encoder})`)
          # ex.: "trecho curto de 0.60s (< 3s): a inicializacao da GPU nao compensa; usando CPU (libx264)"

(E) return EncoderChoice(preferido,
      `GPU disponivel para {alvo.upper()}: {preferido.label}`)
      # ex.: "GPU disponivel para H264: NVENC (NVIDIA)"
```

Ordem de precedência (importante para o port): **CPU explícita (A) → forçado no Avançado (B, ganha mesmo em trecho curto) → GPU-sem-codec (C) → trecho curto (D) → GPU preferida (E)**.

Casos que devolvem `None` (nenhuma opção viável):
1. Path CPU e não há opção CPU para o codec alvo nas opções sondadas.
2. Path GPU + Avançado forçado, a key forçada não existe entre as GPUs sondadas **e** não há opção CPU para o codec.
3. Path GPU, nenhuma GPU com o codec alvo **e** nenhuma CPU com o codec alvo.

Detalhes exatos:
- `seconds` "desconhecido" = `0.0` (falsy) → **não** aplica a regra de trecho curto; GPU ganha (teste `test_gpu_quando_a_duracao_e_desconhecida`).
- O limiar é **estrito**: `seconds < short_job_seconds`; `seconds == 3.0` ainda usa GPU (teste `test_trecho_limite_ainda_usa_gpu`).
- Nos motivos (B), `{advanced}` é a **key** (`"nvenc"`), não o rótulo; nos motivos (B/E) o alvo é maiúsculo (`H264`/`HEVC`).
- Os motivos usam `usuario`/`forcado`/`nao`/`inicializacao` **sem acento** (strings do módulo; copiar exatamente).
- A prioridade decide o `preferido` apenas entre GPUs que produzem o codec alvo.

---

## 3. Sondagem real (`_probe_catalog` / `_test_encoder` et al., ffmpeg_tools_panel.py)

### 3.1 Fluxo

- No `__init__` do painel (linha 1228): `threading.Thread(target=self._load_available_accelerations, daemon=True).start()` — a sondagem roda em thread de fundo no início do app; o resultado é aplicado na UI thread via `self.root.after(0, apply)`.
- `_available_accelerations()` (4363): roda `[ffmpeg, "-hide_banner", "-encoders"]` (timeout 20 s, `CREATE_NO_WINDOW` no Windows), junta `stdout+stderr` e `.lower()`; com exceção → string vazia. Depois monta a lista com `[self._catalog_video_acceleration(option) for option in self._probe_catalog(encoders)]`.
- `_probe_catalog(encoders)` (4372): itera `CATALOG` **na ordem do catálogo** e:
  1. pula a opção se `option.encoder not in encoders` (pré-filtro barato pela lista de encoders do ffmpeg empacotado);
  2. guarda legada de `vaapi`: `if option.key == "vaapi" and (os.name == "nt" or not Path("/dev/dri/renderD128").exists()): continue` (morta hoje: não há entrada vaapi no CATALOG);
  3. chama `_test_encoder(option)` — **encode real** — e só entra quem passa.
  Ao final grava `self.available_encoder_options = opcoes` e devolve a lista (ordem = ordem do catálogo; CPU depois das GPUs).
- `_catalog_video_acceleration(option)` (4387, estático): converte para `VideoAcceleration(chave, label, encoder)` onde `chave = "cpu" if option.path == ENCODER_PATH_CPU else option.key` (ex.: nvenc→`"nvenc"`, amf→`"amf"`, cpu e cpu-mpeg4→`"cpu"`).
- `_load_available_accelerations` (4340), no `apply()`: seta `self.available_accelerations`, `self.acceleration_by_label = {label: profile}`; se o valor atual do combo principal não está em `PATH_LABELS.values()` força `PATH_LABELS[ENCODER_PATH_GPU]` (i.e., `"GPU"`); chama `_refresh_encoder_control_state()` e `_refresh_effective_encoder_label()`.

### 3.2 O teste de existência real (`_test_encoder`, 4391)

Comando exato (1 quadro de vídeo preto 256x256, 0.1 s, saída nula):

```
[ffmpeg, "-hide_banner", "-loglevel", "error",
 "-f", "lavfi", "-i", "color=c=black:s=256x256:d=0.1",
 "-frames:v", "1",
 "-c:v", <encoder da opção>,
 "-f", "null", "-"]
```

(Se `profile.key == "vaapi"`: adiciona `-vaapi_device /dev/dri/renderD128` e `-vf format=nv12,hwupload`; irrelevante hoje, ver acima.) Retorna `True` somente se `returncode == 0`; qualquer exceção/timeout (20 s) → `False`.
Ou seja: "existe de verdade" = o encoder **compila e codifica um quadro sem erro nesta máquina**; não basta aparecer no `-encoders`.

### 3.3 O que aparece no Avançado

- Somente opções **GPU** que passaram na sondagem, uma entrada por **label/vendor** (dedupe — bug relatado de NVENC duplicado h264+hevc foi corrigido): ver `_advanced_labels` na seção 4.
- O catálogo sondado também é o `available` usado por `resolve_encoder` em todas as tarefas.

### 3.4 Quando NADA passa na sondagem

- `available_encoder_options = []` e `available_accelerations = []`.
- UI: `_refresh_encoder_control_state` vê `has_encoder = False` → combo principal `state="disabled"`, esconde menu/rótulo/ajuda de Qualidade e chama `_forget_encoder_extras()` (esconde ajuda do encoder, combo Avançado, rótulo e rótulo de decisão).
- Tarefa: `_resolve_task_encoder` devolve `None` e loga `Nenhum encoder de vídeo disponível para esta tarefa; mantendo a escolha anterior.`.
- Reexecução: `_worker_wrapper` (4214), se `worker_acceleration is None`, tenta `acceleration_by_label[selected_acceleration_label]` e, em último caso, `self._available_accelerations()[0]` (lista vazia nessa borda levantaria `IndexError` → status `Erro: ...`; comportamento de produção para esse extremo **NÃO DETERMINADO no código** — procurei em `_worker_wrapper` 4214-4253 e `_execute_video` 4582).
- No caminho de vídeo, `_execute_video` ainda tem rede de segurança: `profile = self.acceleration or self._cpu_encoder_for("libx264")` (linha 4583).

---

## 4. UI (ffmpeg_tools_panel.py)

### 4.1 Seletor principal (só GPU/CPU) — barra de ferramentas (linhas 1255-1275)

- Rótulo: `"Encoder de vídeo:"` (tk.Label, estilo `Muted.TLabel`).
- Combo `acceleration_combo` (ttk.Combobox): `textvariable=self.acceleration_var`, `values=(PATH_LABELS[ENCODER_PATH_GPU], PATH_LABELS[ENCODER_PATH_CPU])` → visivelmente `("GPU", "CPU")`, `width=6`, nasce `state="disabled"`; inicialização da variável: `StringVar(value=PATH_LABELS[ENCODER_PATH_GPU])` (= `"GPU"`).
- Evento: `<<ComboboxSelected>>` → `_on_encoder_path_changed()` → `_refresh_encoder_control_state()`.
- `_encoder_path()` (2423): devolve `"cpu"` se o texto do combo == `PATH_LABELS[ENCODER_PATH_CPU]` (`"CPU"`), senão `"gpu"` (qualquer outro valor, inclusive vazio, cai em GPU).
- Botão de ajuda `encoder_help_button`: `text="?"`, `width=3`, `command=_show_encoder_help`.

### 4.2 Avançado (só no modo GPU; primeiro item "Automático") (linhas 1265-1271, 2596-2611)

- Constante `ENCODER_ADVANCED_AUTO_LABEL = "Automático"` (linha 2421).
- Rótulo: `"Avançado:"` (Muted), combo `encoder_advanced_combo` `width=16`, `state="readonly"`; variável inicial `StringVar(value=self.ENCODER_ADVANCED_AUTO_LABEL)` = `"Automático"`.
- `_advanced_labels()` (2438): devolve `("Automático",) + rótulos` onde os rótulos são os `option.label` das opções **GPU** sondadas, **sem repetir**, na ordem do catálogo sondado (→ NVENC, QSV, AMF). Docstring: `"Uma entrada por VENDOR (o codec quem decide é a tarefa, não o usuário)."`
- `_advanced_key()` (2428): `"auto"` se vazio ou `"Automático"`; senão procura entre as opções GPU sondadas a de `label` igual ao texto escolhido e devolve a `key` (ex. `"NVENC (NVIDIA)"` → `"nvenc"`); se nada casar → `"auto"`.
- `_refresh_encoder_advanced_controls()` (2596) — regras exatas:
  - se `_encoder_path() != ENCODER_PATH_GPU` (ou widgets ausentes) → `pack_forget` no combo e no rótulo (Avançado **só no modo GPU**);
  - senão: `combo.configure(values=rotulos, state="readonly")`; se o valor atual não está em `rotulos` → volta para `"Automático"`;
  - só **exibe** (`pack`) quando `len(rotulos) > 1` (isto é, existe ao menos um vendor GPU sondado além do Automático) e ainda não está mapeado. Observação literal: uma vez mapeado, ele não é escondido se a lista encolher — `pack_forget` no modo GPU não acontece.
- O combo do Avançado é `pack`ado à direita com `padx=(6,0)`; rótulo à direita com `padx=(12,4)`.

### 4.3 Rótulo discreto de decisão automática (quando a GPU cai na CPU) (`_refresh_effective_encoder_label`, 2531-2554)

- Variável `encoder_effective_var`, texto inicial `"Encoder de vídeo: detectando opções..."`; widget `encoder_effective_label` (Muted).
- Regra: se `_encoder_path() != ENCODER_PATH_GPU` → `pack_forget` (não mostra). No modo GPU, recalcula com `_task_codec_and_seconds(tool ativo)` + `resolve_encoder(... path=GPU ...)`; se a escolha é `None` **ou** é GPU → esconde. Se a escolha cai na **CPU**, mostra um rótulo `→ {label} ({encoder}): {reason}` (ex.: `→ CPU (libx264): trecho curto de 2.10s (< 3s): ...`) e o `pack` à direita (`side=RIGHT, padx=(12,4)`) quando ainda não mapeado.
- Chamado em: `_select_ffmpeg_tool` (troca de ferramenta), `_update_cut_controls`, `_load_available_accelerations.apply`.

### 4.4 Texto de ajuda do encoder (botão "?") — `_show_encoder_help` (2449)

`messagebox.showinfo("Encoder de vídeo", <texto abaixo>)`. Texto completo, exato (`\n\n` = linha em branco):

> GPU: usa o encoder de hardware (NVENC/QSV/AMF) sempre que ele existir para o codec necessário — em geral é bem mais rápido. Se a GPU falhar (driver, sessão ocupada), a tarefa é repetida na CPU e o app avisa no log.
>
> CPU: reencoda sempre no processador (libx264/libx265). Mais lento, porém menor arquivo para o mesmo bitrate e sem depender do driver da placa.
>
> Avançado (só no modo GPU): Automático deixa o app escolher sozinho entre os encoders de hardware disponíveis — e usar a CPU quando a GPU não tiver o codec pedido ou quando o trecho a reencodar for curto demais para a inicialização do hardware compensar. Escolher um encoder específico força aquela placa; se ela não estiver disponível, o app cai na CPU avisando no log.

(O `"Automático"` no meio do texto vem da concatenação com `self.ENCODER_ADVANCED_AUTO_LABEL`.)

### 4.5 Habilitação/desabilitação por ferramenta — `_current_tool_uses_video_encoder` (2381-2419)

`tool = self.active_tool_var.get()`. Ferramentas da aba: `"Cortar"`, `"Extrair áudio"`, `"Girar vídeo"`, `"Juntar áudios/vídeos"`, `"Inserir áudio"`, `"Limpar áudio"`.

- **`"Cortar"`**: `False` se `_cut_mode_is_copy()` (modo Sem Reencode = cópia, **não usa encoder**); `False` se `cut_input is None`; senão `True` se `cut_media_profile` existe e `has_video`; senão (sem perfil) `True` se a extensão do arquivo está em `VIDEO_EXTENSIONS`.
- **`"Girar vídeo"`**: delega a `_rotation_uses_video_encoder(metadata_only, degrees, hflip, vflip)` (estático, 2357): `(not metadata_only) and (degrees % 360 != 0 or hflip or vflip)` — comentário: *"Sem filtro visual (grau 0 e sem espelhamento) o worker usa -c copy: encoder não tem efeito."* `degrees` inválido → 0.
- **`"Juntar áudios/vídeos"`**: `is_audio_only` = há entradas e **todas** são áudio (perfil `has_audio and not has_video`, ou extensão em `AUDIO_EXTENSIONS` quando não há perfil); `has_reencode` = `join_reencode_var` verdadeiro, ou (SmartJoin) `join_seconds > 0.001` (vírgula trocada por ponto), senão `False`; retorna `not is_audio_only and has_reencode`.
- **Demais** (`"Extrair áudio"`, `"Inserir áudio"`, `"Limpar áudio"`): caem no `return False` final — não produzem `-c:v` dependente do seletor.

Efeito em `_refresh_encoder_control_state` (2556):
- `uses_video_encoder = _current_tool_uses_video_encoder()`; `has_encoder = bool(self.available_accelerations)`.
- Se **não** usa encoder de vídeo **ou** não há encoder sondado: `acceleration_combo` → `state="disabled"`; esconde (`pack_forget`) `quality_menu_button`, `quality_label`, `quality_help_button`; `_forget_encoder_extras()` (esconde `encoder_help_button`, `encoder_advanced_combo`, `encoder_advanced_label`, `encoder_effective_label`).
- Senão: `acceleration_combo` → `state="readonly"`; reexibe os widgets de Qualidade (se não mapeados), reexibe o "?" do encoder; chama `_refresh_encoder_advanced_controls()`.
- Chamado por: troca de ferramenta (`_select_ffmpeg_tool`), `_update_cut_controls`, `_on_encoder_path_changed`, `_load_available_accelerations.apply`.

### 4.6 Restrições do modo de corte na UI (resumo; detalhes na seção 6)

- `_update_cut_controls` (2369): `fast = _cut_mode_is_copy()`; `has_video` do `cut_media_profile`; enquanto executa (`running`) tudo fica limitado.
  - `cut_audio_policy_combo` (`"Precisão máxima (AAC)"` / `"Copiar áudio (limites por pacote)"`): readonly só quando `has_video and not fast and not running`; senão disabled.
  - `cut_stream_policy_combo` (`"Vídeo e áudio"` / `"Todos os streams (somente modo rápido)"`): readonly só quando `has_video and fast and not running`; senão disabled; quando **não** é modo rápido, força `"Vídeo e áudio"`.
  - Também chama `_refresh_encoder_control_state()` e `_refresh_effective_encoder_label()`.
- `_set_running_ui` (4041): em execução, `cut_mode_combo` → `state="disabled"` (volta `readonly` depois).
- `_confirm_preview_selection` (4117): se há seleção desenhada e o modo é Sem Reencode, muda o modo para `"Reencode Completo"` e atualiza os controles, avisando: `"\n\nA seleção exige reencodar: o modo foi alterado para 'Reencode Completo'."`

---

## 5. Resolução por tarefa

### 5.1 De onde vêm codec e duração — `_task_codec_and_seconds` (2502)

| Ferramenta | codec | segundos |
|---|---|---|
| `"Cortar"` | `_preserved_codec(self.cut_media_profile)` | `_cut_job_seconds()` |
| `"Girar vídeo"` | `_preserved_codec(self.rotate_media_profile)` | `_rotate_job_seconds()` |
| qualquer outra | `"h264"` | `0.0` |

- `_preserved_codec(media)` (estático, 2474): `normalize_codec(media.video_codec or "") or "h264"` — **o codec vem do ARQUIVO** quando reconhecível (HEVC continua HEVC); senão H.264. (Também usado pela resolução do SmartCut via `_smartcut_edge_encoder`.)
- `_cut_job_seconds` (2478): `0.0` no modo Sem Reencode; senão `max(0.0, fim - início)` com `float(str(var.get()).replace(",", ".") or 0)`; erro de parse → `0.0`.
- `_rotate_job_seconds` (2489): duração do `rotate_media_profile`; se `fim > inicio` → `min(fim, duracao or fim) - inicio` (com fallback para a duração); senão a duração inteira.

### 5.2 Onde roda: UI antes da worker — `_resolve_task_encoder` (2511) + `run_current_tool` (4145)

- `run_current_tool` roda na UI thread e, **antes** de criar a thread da worker, faz `self.worker_acceleration = self._resolve_task_encoder(tool)` (linha 4160). Comentário no código: `"Capture Tk state on the UI thread. Workers use only plain Python values."`
- `_resolve_task_encoder`: pega `(codec, segundos) = _task_codec_and_seconds(tool)` e chama `resolve_encoder(codec=..., path=self._encoder_path(), available=list(self.available_encoder_options), advanced=self._advanced_key(), seconds=segundos)`.
  - Se `None`: loga `Nenhum encoder de vídeo disponível para esta tarefa; mantendo a escolha anterior.` e devolve `None`.
  - Senão: loga `Encoder de vídeo: {label} ({encoder}) — {reason}` (travessão `—`) e devolve `VideoAcceleration(escolha.option.key, escolha.option.label, escolha.option.encoder)` (atenção: `key` de CPU pode ser `"cpu"` ou `"cpu-mpeg4"`).
  - `_log_encoder_choice` (2464) escreve no log de atividade do app (`app._append_activity_log`, sem tag → cor automática), com `try/except` silencioso.
- Para a worker, `worker_options` recebe (4187-4192): `"encoder_path": self._encoder_path()`, `"encoder_advanced": self._advanced_key()`, `"encoder_options": [(key, label, path, codec, encoder, priority) ...]` (tuplas explícitas, equivalentes a `options_to_tuples`).
- `_worker_encoder_inputs` (4776): lê de volta com defaults `ENCODER_PATH_GPU` / `ENCODER_ADVANCED_AUTO` / lista vazia via `options_from_tuples`.
- `_worker_wrapper` (4214): aplica `self.acceleration = worker_acceleration` se veio da UI; caso contrário cai no comportamento antigo por rótulo. Também decide a mensagem de status com `worker_tool_uses_video_encoder` (capturado na UI em `run_current_tool`): com encoder → `f"Encoder selecionado: {self.acceleration.label}; qualidade: {quality}"`; sem → `"Encoder de vídeo: não aplicável"`. No sucesso, o aviso de tempo inclui `Encoder: {encoder}` (ou `"não aplicável"`).

### 5.3 SmartCut: resolução POR TRECHO — `_smartcut_edge_encoder` (4783)

- Assinatura: `_smartcut_edge_encoder(self, codec_family: str, segundos: float = 0.0) -> VideoAcceleration | None`. Chamado em `_cut_video_smartcut` para cada borda reencodada com a duração **daquele trecho**: `encoder=self._smartcut_edge_encoder(codec_family, duracao)` (linha 4947; o teste exige o texto `_smartcut_edge_encoder(codec_family, duracao)`).
- Lógica:
  1. `path, advanced, disponiveis = self._worker_encoder_inputs()` (preferência da UI + catálogo sondado);
  2. se há `disponiveis`: `resolve_encoder(codec=codec_family, path=path, available=disponiveis, advanced=advanced, seconds=segundos)`; se devolveu escolha → usa a escolha; se `segundos` (truthy) → loga `Encoder da borda ({segundos:.2f}s): {escolha.option.encoder} — {escolha.reason}`;
  3. senão (ou sem escolha): fallback `_smart_join_acceleration_for_codec(codec_family)` — e valida a **compatibilidade de codec** (o miolo copiado manda): `h264` exige encoder começando com `("libx264", "h264_")`; `hevc` exige `("libx265", "hevc_")`; qualquer outro → `None`.
- Quando devolve `None`, `_cut_video_smartcut` cai no Reencode Completo com log: `"O encoder selecionado não produz o mesmo codec do arquivo (o miolo é copiado): usando o Reencode Completo."` (ver 6.4).

### 5.4 Fallback CPU — `_cpu_encoder_for` (4568)

- `_cpu_encoder_for(encoder)`: determina o codec pelo encoder que falhou — `"hevc"` se `encoder.lower().startswith(("libx265", "hevc_"))`, senão `"h264"`; procura a **primeira** opção sondada com `path == CPU` e `codec == <codec>` (ordem do catálogo sondado: `libx264`/`libx265` e, se for o caso, `mpeg4`). Se não houver, devolve o primeiro de `available_accelerations` com `key == "cpu"`; em último caso `VideoAcceleration("cpu", "CPU", "libx264")`.
- Uso duplo: retry pós-falha de hardware (5.5) e rede de segurança de `_execute_video` (linha 4583).

### 5.5 Falha de hardware em execução — `_execute_video` (4582) + `_is_hardware_encoder_error` (4598)

- `_execute_video(label, builder, ...)`: `profile = self.acceleration or self._cpu_encoder_for("libx264")`; tenta `self._execute(builder(profile), ...)`.
- Se `RuntimeError`: **re-levanta** quando `str(profile.key).startswith("cpu")` (já era CPU) **ou** quando a mensagem **não** é erro de hardware. Senão:
  - `cpu_fallback = self._cpu_encoder_for(profile.encoder)` (mesmo codec);
  - `motivo` = primeira linha da mensagem;
  - loga `{profile.label} falhou ({motivo}); repetindo na CPU ({cpu_fallback.encoder}) SOMENTE nesta tarefa.`;
  - repete **naquela tarefa** com `builder(cpu_fallback)` e label `f"{label} (CPU)"`.
  - **Não** altera a preferência armazenada (o teste proíbe `self.acceleration = cpu_fallback`): a próxima tarefa volta a tentar a GPU. Comentário: `"NÃO troca a preferência do usuário: a próxima tarefa volta a tentar a GPU."`
- `_is_hardware_encoder_error(message)` (estático): `lower = message.lower()`; é erro de hardware se contiver **qualquer** um destes marcadores (tupla exata): `"nvenc"`, `"cuda"`, `"qsv"`, `"mfx"`, `"amf"`, `"vaapi"`, `"d3d11"`, `"d3d12"`, `"hardware device"`, `"device setup failed"`, `"encoder initialization"`, `"initializing output stream"`, `"no capable devices"`, `"session limit"`.
- Quem usa `_execute_video`: `_cut_video_precise` (corte com reencode) e o worker de giro (não detalhado aqui). O SmartCut chama `self._execute` direto nos segmentos (sem esse retry automático por trecho — **NÃO DETERMINADO** se há retry equivalente para bordas; procurei em `_cut_video_smartcut` 4879-4992 e só vi `_execute`).
- Obs. de port: os marcadores ainda incluem `"mfx"`/`"vaapi"`/`"amf"`; no Android os equivalentes seriam erros do MediaCodec/driver (nomes ainda não definidos).

### 5.6 Fallback de código para SmartJoin — `_smart_join_acceleration_for_codec` (6182)

- Se `self.acceleration` existe e é compatível com a família pedida (`h264`: começa com `("libx264", "h264_", "mpeg4")`; `hevc`: `("libx265", "hevc_")`) → usa o acceleration atual.
- Senão devolve CPU: `VideoAcceleration("cpu", "CPU (HEVC)", "libx265")` para hevc; `VideoAcceleration("cpu", "CPU (fallback)", "libx264")` caso contrário.
- É esse fallback que o SmartCut usa quando não há catálogo sondado na worker; a checagem de "mesmo codec" de `_smartcut_edge_encoder` é feita **em cima do encoder** devolvido por ele (por isso o caso "mpeg4 para arquivo h264" → `None`).

### 5.7 Args de qualidade/encoder usados nos reencodes (`_video_args`, 4455) — necessário para fidelidade do port

`quality = selected_video_quality` (`"Máxima"`, `"Muito alta"`, `"Alta"`, `"Média"`, `"Econômica"`; padrão `"Alta"`). Escala de bitrate por hardware: `{"Máxima": 1.60, "Muito alta": 1.25, "Alta": 1.00, "Média": 0.70, "Econômica": 0.45}`; `rate_control = ["-b:v", alvo, "-maxrate", alvo, "-bufsize", <2× alvo>]`.
- `libx264`: `["-c:v", "libx264", "-preset", "medium", "-crf", <16|18|20|23|26>]`.
- `libx265`: `["-c:v", "libx265", "-preset", "medium", "-crf", <18|20|22|25|28>]`.
- `nvenc`: se `-cq` e `-rc` aparecem em `ffmpeg -h encoder=<enc>` → `["-c:v", enc, "-preset", "p4", "-rc", "vbr", "-cq", <16|19|22|25|28>, *rate_control]`; senão `["-c:v", enc, "-preset", "p4", *rate_control]`.
- `qsv`: `["-c:v", enc, "-global_quality", <17|20|23|26|29>, *rate_control]` (comentário: `-global_quality` é genérica, não aparece em `-h encoder=`).
- `amf`: se `-qvbr_quality_level` e `-rc` existem → `["-c:v", enc, "-quality", "balanced", "-rc", "qvbr", "-qvbr_quality_level", <AMF_QVBR_LEVELS>, *rate_control]` com `AMF_QVBR_LEVELS = {"Máxima": 16, "Muito alta": 22, "Alta": 28, "Média": 34, "Econômica": 40}` (menor = melhor); senão `["-c:v", enc, "-quality", "balanced", *rate_control]`.
- Fallback final (encoder desconhecido): `["-c:v", "mpeg4", *rate_control]`.
- Auxiliares: `_buffer_for_bitrate` = 2× o valor (regex `(\d+(?:\.\d+)?)([kKmM])`; inválido → `"2M"`); `_scaled_bitrate` multiplica e normaliza `M→k` antes de arredondar.

---

## 6. Modos de corte

### 6.1 Constantes (linhas 98-112)

```
CUT_MODE_SMART = "SmartCut"
CUT_MODE_REENCODE = "Reencode Completo"
CUT_MODE_COPY = "Sem Reencode"
CUT_MODES = (CUT_MODE_SMART, CUT_MODE_REENCODE, CUT_MODE_COPY)   # ordem exibida
SMARTCUT_MIN_EDGE = 0.05   # margem mínima para valer reencodar a borda / haver miolo copiável
```

Comentário: `"Modos de corte da aba Cortar (na ordem exibida; SmartCut é o padrão)."` Padrão da UI: `self.cut_mode_var = StringVar(value=CUT_MODE_SMART)` (linha 1138). O teste exige literalmente `StringVar(value=CUT_MODE_SMART)` no fonte.

### 6.2 UI do seletor de modo (`_build_cut_tab`, 2001-2014)

- Label `"Modo:"`; combo `cut_mode_combo` com `values=CUT_MODES`, `state="readonly"`, `width=20`; bind `<<ComboboxSelected>>` → `_update_cut_controls`.
- Botão de ajuda `cut_mode_help_button`: `text="?"`, `width=3`, `command=_show_cut_mode_help` — `_show_cut_mode_help` (2366) faz `messagebox.showinfo("Modos de corte", CUT_MODE_HELP)`.
- `CUT_MODE_HELP` (texto completo, exato — `\n\n` = linha em branco):

> SmartCut: corte preciso e rápido, mas EXPERIMENTAL — copia os trechos que já começam em keyframe e reencoda apenas as bordas até os tempos exatos.
>
> Reencode Completo: reencoda todo o trecho — lento e preciso.
>
> Sem Reencode: copia os streams sem reencodar — rápido e menos preciso, porque início e fim escorregam até o keyframe/pacote disponível.

- `_cut_mode_is_copy` (2362): `str(self.cut_mode_var.get()).startswith(CUT_MODE_COPY)` — é o predicado de "modo rápido".

### 6.3 Despacho — `_cut_worker` (4688)

1. Validações: sem arquivo → `"Selecione o arquivo para cortar"`; `_seconds` de início/fim; `"O fim deve ser maior que o início"`; se `media.duration > 0` e `(start >= duration - 0.001 or end > duration + 0.05)` → `f"O intervalo excede a duração do arquivo ({self._clock(media.duration)})."` onde `_clock` formata `M:SS.mmm`.
2. `cut_mode` vem de `worker_options["cut_mode"]` (capturado na UI) ou da variável; `fast_copy = cut_mode.startswith(CUT_MODE_COPY)`; `smart_cut = not fast_copy and not cut_mode.startswith(CUT_MODE_REENCODE)` (⇒ default SmartCut).
3. Se `crop = _worker_crop("cut_crop")` (seleção de área) e `fast_copy` → loga `"A seleção de área exige reencodar: usando o Reencode Completo."`, `fast_copy=False`, `smart_cut=False`. Se `crop and smart_cut` → loga `"A seleção de área exige reencodar todo o trecho: usando o Reencode Completo."`, `smart_cut=False`. (O miolo copiado do SmartCut não pode ser recortado por filtro.)
4. `preserve_all_streams = fast_copy and stream_policy.startswith("Todos os streams")` (valor de UI: `"Todos os streams (somente modo rápido)"`).
5. Extensão de saída: vídeo → `self._metadata_rotate_output_suffix(source.suffix)` no modo rápido (preserva `.mp4/.mkv/.webm/.mov/.m4v`, senão `.mp4`), `.mp4` nos demais; áudio puro → `_audio_only_output_extension` (extensão de áudio da fonte ou `.m4a`). Nome: `f"{source.stem}_cortado{ext}"` com `_safe_output` (sufixo `_2`, `_3`, ... se já existir).

Ramificação (o coração dos 3 modos):

| modo | caminho executado | label/observação |
|---|---|---|
| `fast_copy` (Sem Reencode) | comando de cópia (`-c copy`) | `"Cortando sem reencodar"` + log `"Corte rápido: codecs preservados; os limites são aproximados ao keyframe/pacote disponível."` |
| vídeo + `smart_cut` (SmartCut) | `_cut_video_smartcut(source, output, start, end, media, audio_precise=not audio_policy.startswith("Copiar áudio"))` | reencoda bordas, copia miolo |
| vídeo restante (Reencode Completo, ou força por seleção) | `_cut_video_precise(source, output, start, end, media, copy_audio=audio_policy.startswith("Copiar áudio"), crop=crop)` | `"Cortando vídeo com precisão"` |
| sem vídeo (áudio puro) | reencoda áudio pelo codec-fonte (`_audio_codec_args_for_source_codec` ou `_audio_codec_args`) | `"Cortando áudio"` |

**Comando do Sem Reencode (exato):** `[ffmpeg, "-hide_banner", "-y", "-ss", <start>, "-i", <src>, "-t", <duração>]` + :
- vídeo, todos os streams: `["-map", "0", "-c", "copy"]`;
- vídeo, senão: `["-map", "0:v:0", "-map", "0:a?", "-sn", "-dn", "-c", "copy"]`;
- e, se a extensão de saída ∈ `{.mp4, .mov, .m4v}`: `["-movflags", "+faststart"]`;
- áudio puro: `["-map", "0:a:0", "-vn", "-c", "copy"]`.
Comentário sobre os limites: `"Corte rápido: codecs preservados; os limites são aproximados ao keyframe/pacote disponível."` (item `6.1` da ajuda: `"início e fim escorregam até o keyframe/pacote disponível"`).
Obs.: no modo rápido a seleção de área já foi convertida para Reencode Completo antes (passo 3 / `_confirm_preview_selection` na UI).

### 6.4 SmartCut — internos (`_smartcut_codec_family` 4767, `_extract_keyframes` 5041, `_smartcut_segment_arguments` 4813, `_cut_video_smartcut` 4879)

- `_smartcut_codec_family(media)`: `"h264"` para `("h264","avc1","avc")`; `"hevc"` para `("hevc","h265","hvc1","hev1")`; senão `None` (ex.: `vp9` → `None` ⇒ Reencode Completo).
- Keyframes: `_extract_keyframes(source)` roda `[ffmpeg, "-hide_banner", "-skip_frame", "nokey", "-i", src, "-vf", "showinfo", "-an", "-f", "null", "-"]` e extrai `pts_time:([\d.]+)` de stdout+stderr; devolve lista ordenada e sem repetição.
- Fronteiras: `limite = max(SMARTCUT_MIN_EDGE, 0.02)`; `keyframes = [v for v in keyframes if start - limite <= v <= end + limite]` (inclui keyframe EXATO no início/fim ⇒ sem borda); `cabeca_fim = min(keyframes)`, `cauda_inicio = max(keyframes)`.
- Cai no Reencode Completo (`_cut_video_precise`) — com log exato — quando: `codec_family` é `None`, `encoder_bordas` é `None`, não há keyframes, ou `cauda_inicio - cabeca_fim <= SMARTCUT_MIN_EDGE`. Log do caso geral: `"SmartCut não encontrou keyframes úteis (ou o codec não é compatível): usando o Reencode Completo."`; log do caso "encoder não serve": `"O encoder selecionado não produz o mesmo codec do arquivo (o miolo é copiado): usando o Reencode Completo."`
- Trechos montados: `"01_cabeca.ts"` (`start` → `cabeca_fim`, reencodado, label `"Reencodando a borda inicial"`) **se** `cabeca_fim - start > SMARTCUT_MIN_EDGE`; `"02_miolo.ts"` (`cabeca_fim` → `cauda_inicio`, `-c:v copy`, label `"Copiando o miolo"`); `"03_cauda.ts"` (`cauda_inicio` → `end`, reencodado, label `"Reencodando a borda final"`) **se** `end - cauda_inicio > SMARTCUT_MIN_EDGE`. Pasta de trabalho `smartcut_<uuid8>` no diretório da saída, removida em `finally` com `shutil.rmtree(trabalho, ignore_errors=True)`.
- Log de proporção (exato): `f"SmartCut: {copiado:.2f}s copiados sem reencode e {total - copiado:.2f}s reencodados ({len(trechos)} trechos)."`; `total = max(0.01, end - start)`.
- Argumentos de CADA trecho (`_smartcut_segment_arguments`, ordem exata):
  1. `[ffmpeg, "-hide_banner", "-y", "-noautorotate", "-display_rotation:v:0", "0"]` — orientação neutralizada no trecho (volta no mux final);
  2. `["-ss", start, "-i", source, "-t", duração]` — **input seek** SEMPRE antes do `-i` (comentário: com output seek o `-c:v copy` recua até o keyframe anterior e o áudio dessincroniza);
  3. `["-map", "0:v:0"]`; se `media.has_audio`: `["-map", "0:a?"]`;
  4. vídeo: reencode → `_video_args(encoder_escolhido, media.video_bitrate)` + `["-pix_fmt", media.pix_fmt or "yuv420p"]` + (se SAR ≠ `1:1`/`0:1`/`N/A`/vazio) `["-vf", f"setsar={media.sar.replace(':','/')}"]` + (se fps) `["-r", media.fps]`; cópia → `["-c:v", "copy"]`;
  5. áudio: sem áudio → `["-an"]`; com `audio_precise` → `["-c:a", "aac", "-b:a", media.audio_bitrate, "-ar", str(media.audio_rate), "-ac", str(media.audio_channels)]`; senão `["-c:a", "copy"]`;
  6. remate fixo: `["-bsf:v", <"hevc_mp4toannexb" se hevc senão "h264_mp4toannexb">, "-avoid_negative_ts", "make_zero", "-mpegts_flags", "+resend_headers+initial_discontinuity", "-muxdelay", "0", "-muxpreload", "0", "-f", "mpegts", <segmento>]`.
- Mux final (concat TS, label `"Montando o arquivo final"`, 1 passo extra no progresso): `[ffmpeg, "-hide_banner", "-y", "-display_rotation:v:0", str(media.rotation or 0), "-fflags", "+genpts", "-f", "concat", "-safe", "0", "-i", <lista.txt>]` + `["-map", "0:v:0"]` + (se áudio) `["-map", "0:a?"]` + `["-c:v", "copy"]` + sem áudio `["-an"]` / com áudio `["-c:a", "copy"]` + `["-bsf:a", "aac_adtstoasc"]` quando `audio_precise` ou `media.audio_codec == "aac"` + **`["-tag:v", "hvc1"]` quando `codec_family == "hevc"`** + `["-avoid_negative_ts", "make_zero", "-t", <total>, "-max_interleave_delta", "0", "-video_track_timescale", "90000", "-movflags", "+faststart", "-map_metadata", "0", "-map_chapters", "-1", <saída>]`.
  - `lista.txt` = linhas `file '<caminho>'` com `_concat_escape` (barras normalizadas, `'` → `'\''`).
  - O `-t total` fecha o arquivo no tempo pedido (comentário: trechos podem trazer ms a mais de áudio na emenda).

### 6.5 Reencode Completo — `_cut_video_precise` (4994)

- Avisos/logs antes de executar: crop → `f"Recorte por seleção: {crop[2]} x {crop[3]} pixels a partir de ({crop[0]}, {crop[1]})."`; múltiplas faixas de áudio → `f"{source.name} possui {media.audio_streams} faixas de áudio; todas serão {"copiadas nos limites de pacote"|"preservadas e reencodadas em AAC"}."`; legendas/dados → `"Legendas, anexos e streams de dados não são preservados no corte MP4."`.
- Se `copy_audio` e o codec de áudio não está em `_MP4_SAFE_AUDIO_CODECS = {"aac","mp3","ac3","eac3","alac","flac","opus"}` → erro: `f"O codec de áudio '{media.audio_codec}' não pode ser copiado para MP4. Use 'Precisão máxima (AAC)'."`.
- Comando (builder para `_execute_video`): `[ffmpeg, "-hide_banner", "-y", *input_args, "-ss", start, "-i", src, "-t", end-start, "-map", "0:v:0?", "-map", "0:a?", "-sn", "-dn", *filter_args, *_video_args(profile, media.video_bitrate), *hevc_tag_arguments(profile.encoder, output.suffix), *audio_args, "-map_metadata", "0", "-map_chapters", "-1", "-movflags", "+faststart", <saída>]`; filtro = `selection_crop_filter(crop)` (⇒ `crop=largura:altura:x:y`) ou `"null"`; áudio = `["-c:a","copy"]` (política Copiar) ou AAC com `bitrate`/`rate`/`channels` da fonte.
- Roda dentro de `_execute_video` ⇒ herda o retry CPU-na-falha-de-hardware (5.5). Label: `"Cortando vídeo com precisão"` (+ `" (CPU)"` no retry).

---

## 7. Tag `hvc1` — `hevc_tag_arguments` (video_encoders.py 179-185)

- Assinatura: `hevc_tag_arguments(encoder, suffix=".mp4") -> list[str]`.
- Regra: devolve `["-tag:v", "hvc1"]` **somente** quando o encoder (minúsculo) começa com `("libx265", "hevc_")` **E** o sufixo (minúsculo) está em `(".mp4", ".mov", ".m4v")`; caso contrário `[]`.
- Docstring: `"Tag `hvc1` para HEVC em MP4 (sem ela alguns players nao abrem o arquivo)."`
- Onde entra:
  1. `_cut_video_precise` (linha 5034): `*hevc_tag_arguments(profile.encoder, output.suffix)` — sempre no comando do Reencode Completo de vídeo;
  2. `_cut_video_smartcut` (4977): no concat final, `if codec_family == "hevc": concat += ["-tag:v", "hvc1"]` (mux final re-copia vídeo);
  3. SmartJoin (`_smart_join_concat_arguments`, linha 6511): `if target["codec_family"] == "hevc": args += ["-tag:v", "hvc1"]`.
- Não entra no modo Sem Reencode (não há reencode; o bitstream copiado mantém a tag de origem).

---

## 8. Regras que os testes travam

### 8.1 `tests/test_video_encoders.py`

- **Catálogo completo**: para as famílias `("nvenc","qsv","amf")` tem de existir `(família, "h264")` e `(família, "hevc")`; e `("cpu","h264")` + `("cpu","hevc")`.
- **Só codecs suportados e sem repetição**: cada `codec` ∈ `("h264","hevc")`; cada `path` ∈ `("gpu","cpu")`; nenhum par `(key, codec)` repetido no `CATALOG`.
- **Ordem de prioridade GPU**: `catalog_options(codec="h264", path=ENCODER_PATH_GPU)` → keys exatamente `["nvenc","qsv","amf"]`.
- **`normalize_codec`**: `"H264"`→`"h264"`; `"h265"`→`"hevc"`; `"hvc1"`→`"hevc"`; `"vp9"`→`""`.
- **Serialização**: ida-e-volta `options_from_tuples(options_to_tuples(x)) == x`; item malformado `[("x",)]` → `[]`; `None` → `[]`.
- **GPU para trabalho longo**: h264, GPU, `[CPU_H264, GPU_H264]`, `seconds=30.0` → `h264_nvenc`.
- **Duração desconhecida**: sem `seconds` → ainda `h264_nvenc` (0.0 não aciona trecho curto).
- **Melhor GPU por prioridade**: com qsv (20) disponível junto do nvenc (10) → `h264_nvenc`.
- **Codec certo do par**: `hevc` GPU → `hevc_nvenc`; `hevc` CPU → `libx265`.
- **CPU escolhida pelo usuário**: path CPU → `libx264` e `reason` contém `"CPU"`.
- **Trecho curto explica a ida para a CPU**: `seconds=0.6` → `libx264`; `reason` contém `"curto"`; `SHORT_JOB_SECONDS == 3.0`.
- **Trecho no limite ainda usa GPU**: `seconds == SHORT_JOB_SECONDS` (3.0) → `h264_nvenc` (comparação estrita `<`).
- **GPU sem o codec pedido cai na CPU com motivo**: h264 na GPU + pedido hevc → `libx265`; `reason` contém `"nao tem encoder HEVC"`.
- **Forçado no Avançado é respeitado**: `advanced="qsv"`, `seconds=0.4` → `h264_qsv` (forçado vence até o curto-circuito de trecho curto); `reason` contém `"forcado"`.
- **Forçado indisponível avisa e vai para CPU**: `advanced="nvenc"` com somente `[CPU_H264]` → `libx264`; `reason` contém `"nao passou na sondagem"`.
- **Sem opção viável devolve None**: path CPU com `available=[]` → `None`.
- **`mpeg4` é último recurso**: path CPU com `available=[CPU_MPEG4]` → `mpeg4`.
- **Tag hvc1**: `hevc_tag_arguments("libx265",".mp4") == hevc_tag_arguments("hevc_nvenc",".mp4") == ["-tag:v","hvc1"]`; `"libx264",".mp4"` → `[]`; `"libx265",".mkv"` → `[]`.
- **Principal só GPU/CPU**: fonte contém `values=(PATH_LABELS[ENCODER_PATH_GPU], PATH_LABELS[ENCODER_PATH_CPU])` e `StringVar(value=PATH_LABELS[ENCODER_PATH_GPU])`.
- **Avançado só no modo GPU**: `_refresh_encoder_advanced_controls` contém `"ENCODER_PATH_GPU"` e `"pack_forget"`; `_show_encoder_help` contém `"Avançado"`, `"ENCODER_ADVANCED_AUTO_LABEL"`, `"curto"` e `"CPU"`.
- **Resolução na UI antes da worker**: `run_current_tool` contém `"_resolve_task_encoder"` e `"encoder_options"`; `_worker_wrapper` contém `"worker_acceleration"`; `_resolve_task_encoder` contém `"resolve_encoder"` e `"_log_encoder_choice"`.
- **Codec da tarefa vem do arquivo**: `_task_codec_and_seconds` contém `"cut_media_profile"` e `"rotate_media_profile"`; `_preserved_codec` contém `"normalize_codec"`.
- **Falha de hardware não rebaixa a escolha**: `_execute_video` **não** contém `"self.acceleration = cpu_fallback"`; contém `"SOMENTE nesta tarefa"` e `"_cpu_encoder_for"`.
- **CPU do fallback respeita o codec**: `_cpu_encoder_for` contém `"hevc"` e `"ENCODER_PATH_CPU"`.
- **hvc1 nos reencodes**: `hevc_tag_arguments` aparece em `_cut_video_precise` e no fonte.
- **SmartCut resolve o encoder por trecho**: `_cut_video_smartcut` contém `"_smartcut_edge_encoder(codec_family, duracao)"`; `_smartcut_edge_encoder` contém `"resolve_encoder"` e `"seconds=segundos"`.
- **Avançado lista uma entrada por vendor** (regressão: NVENC duplicado): com `[GPU_H264, GPU_HEVC, CPU_H264, CPU_HEVC]` → `_advanced_labels() == ("Automático", "NVENC (NVIDIA)")`; com qsv → `("Automático", "NVENC (NVIDIA)", "QSV (Intel)")`; `_advanced_key()` com texto `"NVENC (NVIDIA)"` → `"nvenc"` e com `"Automático"` → `"auto"` (a escolha do vendor vale para qualquer codec — quem decide o codec é a tarefa).

### 8.2 `tests/test_ffmpeg_cut_modes.py`

- **Rótulos e ordem**: `CUT_MODES == ("SmartCut", "Reencode Completo", "Sem Reencode")`; o fonte contém `values=CUT_MODES`.
- **Padrão SmartCut**: fonte contém `StringVar(value=CUT_MODE_SMART)`.
- **Ajuda**: `CUT_MODE_HELP` contém `"SmartCut"`, `"EXPERIMENTAL"`, `"lento e preciso"` e `"rápido e menos preciso"`; `_build_cut_tab` contém `"cut_mode_help_button"`; `_show_cut_mode_help` contém `"CUT_MODE_HELP"`.
- **Só Sem Reencode é modo de cópia**: `_cut_mode_is_copy()` é `True` apenas para `"Sem Reencode"` (False para SmartCut e Reencode Completo).
- **Encoder ligado nos modos que reencodam**: `_current_tool_uses_video_encoder` contém `"_cut_mode_is_copy"` (SmartCut reencoda as bordas ⇒ usa encoder; só o Sem Reencode não usa).
- **Trecho copiado usa stream copy e TS**: o comando contém `-c:v` seguido de `"copy"`; `-f` seguido de `"mpegts"`; contém `"h264_mp4toannexb"`, `"+resend_headers+initial_discontinuity"`; índice de `"-ss"` **menor** que o de `"-i"` (input seek); contém `"-noautorotate"`.
- **Borda espelha o trecho copiado**: com `1280x720, fps 30000/1001, yuv420p, sar 4:3` → `-pix_fmt yuv420p`; `-r 30000/1001`; contém `"setsar=4/3"`; áudio reencodado com `-c:a aac`, `-ar 48000`, `-ac 2` (mesmos parâmetros do miolo).
- **Áudio copiado quando a política manda**: `audio_precise=False` → `-c:a copy`.
- **Sem áudio**: não contém `"0:a?"`; contém `"-an"`.
- **`_smartcut_codec_family`**: `h264` → `"h264"`; `hevc` → `"hevc"`; `vp9` → `None`.
- **Encoder das bordas tem de ser do mesmo codec**: `libx264` para arquivo h264 OK; `mpeg4` (fallback CPU) para h264 → `None`; `hevc_nvenc` para h264 → `None`; `h264_nvenc` para hevc → `None`; `hevc_nvenc` para hevc OK.
- **Encoder incompatível cai no Reencode Completo**: `_cut_video_precise` chamado exatamente uma vez; `_execute` não chamado; log contém `"mesmo codec"`.
- **Três trechos quando há keyframes**: corte `[1.4, 4.6]` com keyframes em 2,3,4 → 4 execuções (3 trechos + mux final) com rótulos exatamente `["Reencodando a borda inicial", "Copiando o miolo", "Reencodando a borda final", "Montando o arquivo final"]`; miolo `-c:v copy`; mux final `-c:v copy`, `-f concat`, contém `-display_rotation:v:0`, e `-t` igual a `_fmt_seconds(3.2)` (fecha no tempo pedido); log contém `"SmartCut:"`.
- **Sem borda quando o tempo cai no keyframe**: `[2.0, 4.0]` → apenas `["Copiando o miolo", "Montando o arquivo final"]` (2 execuções).
- **Sem keyframes cai no Reencode Completo**: `_extract_keyframes → []` ⇒ `_cut_video_precise` uma vez; log contém `"Reencode Completo"`.
- **Codec incompatível cai no Reencode Completo**: arquivo `vp9` ⇒ `_cut_video_precise` uma vez.
- **Um único keyframe no meio não vale SmartCut**: keyframes só `[3.0]` ⇒ `_cut_video_precise` uma vez.
- **Limpeza garantida**: `_cut_video_smartcut` contém `"shutil.rmtree"` e `"finally"`.
- **Despacho da worker**: SmartCut → `_cut_video_smartcut` uma vez, `_cut_video_precise` e `_execute` nunca; Reencode → `_cut_video_precise` uma vez, SmartCut nunca; Sem Reencode → `_execute` uma vez com `"copy"` no comando, os dois outros nunca.
- **Seleção de área força Reencode Completo**: modo SmartCut + `cut_crop=(0,0,100,100)` ⇒ `_cut_video_precise` uma vez, `_cut_video_smartcut` nunca; log contém `"seleção de área"`.

---

## 9. Notas de mapeamento para o Android (fora do código Windows — orientação, não fonte)

- A árvore de decisão da seção 2, os motivos (seção 2), o gate por ferramenta (4.5), a resolução por tarefa (5) e os modos de corte (6) são **lógica pura** e devem ser portados 1:1; só os nomes de encoder (`h264_nvenc` → e.g. `"h264_mediacodec"`/`"libx264"`) e a sondagem (seção 3: em vez de `ffmpeg -encoders` + encode de 1 quadro, testar o encoder real do MediaCodec/ffmpeg embarcado) mudam. Os rótulos de vendor e as strings de motivo podem ser adaptados ao conjunto de encoders do Android, mantendo o mesmo formato e os mesmos gatilhos.
- `SHORT_JOB_SECONDS` (3.0) é heurística medida no Windows (~0,4-0,6 s de init do NVENC); no Android o custo de init do MediaCodec deve ser remedido (o valor do Windows é o ponto de partida, não a verdade do dispositivo).
- Qualidade/rate-control (5.7) é parametrização do ffmpeg desktop; no Android os equivalentes do mediacodec (bitrate/profile) precisam ser definidos — a ESTRUTURA (escala por qualidade, tabelas de CRF/QP, rate control por família) é a da seção 5.7.

**Itens marcados como NÃO DETERMINADO no código:** (1) comportamento exato quando a sondagem fica vazia e uma tarefa de vídeo tenta rodar (caminho `_worker_wrapper` → `_available_accelerations()[0]`, procura em 4214-4253); (2) retry automático de falha de hardware por trecho do SmartCut (procura em `_cut_video_smartcut` 4879-4992: os trechos usam `_execute` direto, sem `_execute_video`); (3) uso efetivo de `available_keys`/`catalog_options` dentro do painel (importados em 60-63, sem outra ocorrência no arquivo).
