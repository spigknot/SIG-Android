# Especificação — Palco de prévia (player de vídeo) do SIG Windows

Fonte da verdade: `D:\Projetos\SIG Windows\src\ffmpeg_tools_panel.py` (7022 linhas).
Testes que travam essas regras: `D:\Projetos\SIG Windows\tests\test_ffmpeg_preview_stage.py` e `tests\test_ffmpeg_area_selection.py`.
Objetivo: permitir reimplementar o comportamento no SIG Android (Kotlin) sem abrir o app Windows.

Referências de linha abaixo são do arquivo Windows (podem variar com edições; os nomes de função são a âncora).

---

## 1. Constantes (nome = valor exato, com unidades)

| Constante | Valor exato | Significado / unidade |
|---|---|---|
| `PREVIEW_STAGE_BACKGROUND` | `"#f4f7f6"` | cor de fundo do palco (canvas) |
| `PREVIEW_ZOOM_MIN` | `1.0` | zoom mínimo; 1.0 = vídeo inteiro no palco (não há zoom out além disso) |
| `PREVIEW_ZOOM_MAX` | `5.0` | zoom máximo (5x) |
| `PREVIEW_ZOOM_STEP` | `1.25` | fator multiplicativo por passo de roda (1.25x por notch) |
| `PREVIEW_ZOOM_RESTART_MS` | `250` | ms de debounce para reiniciar o pipeline de quadros após zoom/repintura |
| `PREVIEW_SPEED_VALUES` | `(0.25, 0.5, 1.0, 2.0, 4.0)` | velocidades de reprodução disponíveis |
| `PREVIEW_STAGE_MIN_WIDTH` | `240` | largura mínima do palco, px |
| `PREVIEW_STAGE_MIN_HEIGHT` | `135` | altura mínima do palco, px |
| `PREVIEW_STAGE_MAX_HEIGHT` | `760` | altura máxima do palco, px |
| `PREVIEW_STAGE_MARGIN` | `8` | margem lateral/total usada no cálculo de espaço, px |
| `PREVIEW_RENDER_MAX_PIXELS` | `1600 * 900` = `1440000` | orçamento de pixels do pipeline de quadros (mediu-se ~40 fps em 1200x675, ~22 em 1600x900, ~16 em 1920x1080; alvo 15 fps) |
| `PREVIEW_DISPLAY_MAX_PIXELS` | `2600 * 1500` = `3900000` | limite da imagem desenhada (evita `PhotoImage` gigante) |
| `PREVIEW_SELECTION_OUTLINE` | `"#ffd700"` | cor do traço da seleção (amarelo) |
| `PREVIEW_SELECTION_HANDLE_FILL` | `"#ffd700"` | cor das alças |
| `PREVIEW_SELECTION_TAG` | `"preview_selection"` | tag Tk dos itens da seleção |
| `PREVIEW_SELECTION_WIDTH` | `1` | espessura do traço, px (traço fino) |
| `PREVIEW_SELECTION_MIN_SIZE` | `8` | tamanho mínimo da seleção, px (na resolução desenhada) |
| `PREVIEW_SELECTION_HANDLE` | `7` | tolerância de acerto das alças/bordas, px |
| `PREVIEW_SELECTION_HANDLE_SIZE` | `4` | lado do quadradinho da alça, px (desenhado centrado no ponto) |
| `PREVIEW_SELECTION_DRAG_THRESHOLD` | `4` | movimento mínimo, px, para o botão direito virar desenho; abaixo disso é clique |
| `PREVIEW_SELECTION_CURSORS` | `{"n": "sb_v_double_arrow", "s": "sb_v_double_arrow", "e": "sb_h_double_arrow", "w": "sb_h_double_arrow", "nw": "sizing", "ne": "sizing", "sw": "sizing", "se": "sizing", "move": "hand2"}` | cursor por alça |

Modos de corte (usados na confirmação, §9): `CUT_MODE_SMART = "SmartCut"`, `CUT_MODE_REENCODE = "Reencode Completo"`, `CUT_MODE_COPY = "Sem Reencode"`.

Textos de UI do palco:
- palco vazio (Cortar): `"Selecione uma mídia para visualizar"`
- palco vazio (Extrair áudio): `"Escolha um arquivo para visualizar ou ouvir"`
- palco vazio (Girar vídeo): `"Selecione um vídeo para visualizar"`
- hint de áudio (`_preview_show_hint`): `"Prévia de áudio"` (em `_show_video_thumbnail` a variante é `f"{source.name}\nPrévia de áudio"`)
- erro ao gerar thumbnail: `"Não foi possível gerar a prévia deste vídeo"`
- cor do texto de hint/erro: `"#667371"`, fonte `("Segoe UI", 10)`, `justify="center"`, largura `max(160, stage_width - 24)`
- cursor padrão do palco: `"crosshair"`; com pan ativo: `"fleur"`

---

## 2. Estado

### 2.1 `PreviewViewport` (dataclass, linha ~125)
```
zoom: float = 1.0
offset_x: float = 0.0
offset_y: float = 0.0
media_width: int = 0
media_height: int = 0
stage_width: int = 0
stage_height: int = 0
drag_origin: tuple[int, int] | None = None
drag_offsets: tuple[float, float] = (0.0, 0.0)
```
- 1 viewport por palco (Canvas). `media_*` = dimensões **exibidas** da mídia (já com rotação aplicada quando a ferramenta é Girar/Cortar).
- `drag_origin`/`drag_offsets` só existem durante o arrasto (botão esquerdo/meio).

### 2.2 `PreviewSelection` (dataclass, linha ~319)
- Campos: `left`, `top`, `right`, `bottom` — em **FRAÇÕES 0..1 do quadro de vídeo** (não pixels, não tela).
- `width = max(0.0, right - left)`; `height = max(0.0, bottom - top)` (properties).
- `to_view(drawn_width, drawn_height, origin_x, origin_y) -> (left,top,right,bottom)` na tela:
  ```
  escala_x = max(1, int(drawn_width)); escala_y = max(1, int(drawn_height))
  return (left*escala_x + origin_x, top*escala_y + origin_y,
          right*escala_x + origin_x, bottom*escala_y + origin_y)
  ```
- Guardar em frações é o que faz a seleção sobreviver a zoom, arrasto do quadro e redimensionamento da janela/aba — ela permanece sobre os MESMOS pixels.

### 2.3 Onde mora e ciclo de vida (criados em `FfmpegToolsPanel.__init__`, linhas ~1206-1224)
Todos os dicionários são indexados por `Canvas` (um por palco):
- `preview_viewports: dict[Canvas, PreviewViewport]`
- `preview_holders`, `preview_parents` (Frame holder e aba/parent do palco)
- `preview_stills: dict[Canvas, PIL.Image]` (thumbnail congelado, resolução da fonte)
- `preview_frames: dict[Canvas, PIL.Image]` (último quadro vivo)
- `preview_frame_items: dict[Canvas, int]` (id do item de imagem do quadro)
- `preview_selections: dict[Canvas, PreviewSelection]` — **a seleção atual de cada palco**
- `preview_selection_drag: dict[Canvas, dict]` — drag em andamento (efêmero)
- `preview_selection_filters: dict[Canvas, str]` — filtros de giro/espelho com que a seleção foi desenhada
- `preview_speed = 1.0`, `preview_speed_var = StringVar(value="1.0x")`, `preview_playing = False`
- `preview_restart_id`, `preview_generation`, `preview_frame_queue` — infra do pipeline de quadros (não é regra de UI da seleção).

Quando a seleção é LIMPA (`_clear_preview_selection`, linha ~1693) — apaga `preview_selections`, `preview_selection_drag` e `preview_selection_filters` daquele canvas e remove os itens com a tag:
1. `_preview_show_hint` (palco volta a mostrar a mensagem de vazio — ex.: nova mídia sem vídeo, hint de áudio) — linha ~1437;
2. `_reset_preview_view` (nova mídia carregada no palco: zera zoom/offsets e recria o enquadramento) — linha ~1860;
3. menu de contexto `"Desfazer seleção"` (item do menu do botão direito) — linha ~1831;
4. ao COMEÇAR um novo desenho (`pending` → `draw`): a seleção anterior é descartada no primeiro movimento — linha ~1773 (`self.preview_selections.pop(canvas, None)`).

Quando a seleção NÃO é limpa:
- `_stop_preview` (pausar/trocar de ferramenta) só apaga `preview_selection_drag` e reseta o cursor para `"crosshair"` — a seleção permanece desenhada (linha ~2929-2935);
- zoom, pan e redimensionamento de janela: a seleção é reexpressa/redesenhada sobre os mesmos pixels.

---

## 3. Geometria (fórmulas exatas)

Funções puras de módulo (linhas ~140-295).

### 3.1 `preview_aspect(media_width, media_height)`
`media_width / media_height` se ambos > 0; senão `16.0 / 9.0`.

### 3.2 `preview_stage_size(available_width, available_height, media_width, media_height) -> (int, int)`
Maior palco com a proporção da mídia que cabe na caixa; encosta em pelo menos uma borda (é o que faz o vídeo preencher o espaço sem barras). Ordem exata:
```
aspect = preview_aspect(media_width, media_height)
width = max(PREVIEW_STAGE_MIN_WIDTH, int(available_width))
height = width / aspect
limit = max(PREVIEW_STAGE_MIN_HEIGHT, int(available_height))
if height > limit:            height = limit;  width = height * aspect
if width < PREVIEW_STAGE_MIN_WIDTH:  width = PREVIEW_STAGE_MIN_WIDTH;  height = width / aspect
if height < PREVIEW_STAGE_MIN_HEIGHT: height = PREVIEW_STAGE_MIN_HEIGHT; width = height * aspect
return max(4, int(round(width))), max(4, int(round(height)))
```
Os mínimos (240x135) vencem a caixa (janela pequena → palco mínimo e a aba rola).
Exemplos travados por teste: `preview_stage_size(1200, 1000, 1920, 1080) = (1200, 675)`; `(1200, 400, 1920, 1080) = (711, 400)`; `(1000, 800, 1080, 1920) = (450, 800)`; `(1200, 1000, 0, 0) = (1200, 675)`; `(0, 0, 1920, 1080) = (240, 135)`.

### 3.3 `preview_zoom_clamped(zoom)`
`max(PREVIEW_ZOOM_MIN, min(PREVIEW_ZOOM_MAX, float(zoom)))` → clamp **1.0..5.0**.

### 3.4 `preview_drawn_size(stage_width, stage_height, zoom) -> (largura, altura, zoom_efetivo)`
Tamanho desenhado do quadro, PAR, limitado pelo orçamento de exibição:
```
stage_width  = max(2, int(stage_width)); stage_height = max(2, int(stage_height))
budget    = (PREVIEW_DISPLAY_MAX_PIXELS / max(1, stage_width * stage_height)) ** 0.5
effective = min(preview_zoom_clamped(zoom), max(PREVIEW_ZOOM_MIN, budget))
width     = max(2, int(round(stage_width  * effective)))
height    = max(2, int(round(stage_height * effective)))
return width - width % 2, height - height % 2, effective     # FFmpeg scale/pad exige par
```
Exemplos: `preview_drawn_size(983, 553, 1.0) = (982, 552, 1.0)`; `(983, 553, 1.25) = (1228, 690, 1.25)`; `(400, 240, 99) = (..., ..., 5.0)`; `(400, 240, 5.0) = (2000, 1200, 5.0)`; `(1000, 600, 0.01)` → zoom efetivo `1.0`. Palco grande: o orçamento pode reduzir o zoom efetivo abaixo do pedido (mas nunca abaixo de 1.0).

### 3.5 `preview_render_size(drawn_width, drawn_height, media_width, media_height) -> (largura, altura)`
Resolução que o pipeline de quadros (FFmpeg) produz — nunca acima da nativa e do orçamento:
```
width = max(2, int(drawn_width)); height = max(2, int(drawn_height))
scale = 1.0
if media_width > 0: scale = min(scale, media_width / width)
pixels = width * height
if pixels > PREVIEW_RENDER_MAX_PIXELS:
    scale = min(scale, (PREVIEW_RENDER_MAX_PIXELS / pixels) ** 0.5)
width  = max(2, int(round(width  * scale)))
height = max(2, int(round(height * scale)))
return width - width % 2, height - height % 2
```
Exemplos: `preview_render_size(3000, 1687, 0, 0) = (1600, 900)`; `(1200, 675, 640, 360) = (640, 360)`; `(982, 552, 1280, 720) = (982, 552)`; `(1228, 690, 1280, 720) = (1228, 690)`.
Nota: o limite de "não ampliar acima da fonte" usa só `media_width` (o código não compara `media_height`).

### 3.6 `preview_clamped_offset(stage, drawn, offset) -> float`
Deslocamento válido: nunca deixa aparecer fundo no palco enquanto ampliado:
```
if drawn <= stage: return 0.0
return float(min(0, max(int(stage) - int(drawn), int(round(offset)))))
```
Ou seja, offset ∈ [stage-drawn, 0] (ambos negativos ou 0). Exemplos: `preview_clamped_offset(983, 1228, -9999) = -245`; `(983, 1228, 50) = 0`; `(983, 500, -30) = 0`.

### 3.7 `preview_view_rect(stage_w, stage_h, drawn_w, drawn_h, offset_x, offset_y) -> (x, y)`
Canto superior esquerdo do quadro no palco:
```
x = (int(stage_w) - int(drawn_w)) // 2   se drawn_w <= stage_w   → CENTRALIZADO
  = int(preview_clamped_offset(stage_w, drawn_w, offset_x))      caso contrário
y idem para altura
```
Exemplos: `preview_view_rect(983, 553, 786, 442, 0, 0) = (98, 55)`; `(983, 553, 1228, 690, -9999, -9999) = (-245, -137)`.

### 3.8 `preview_zoom_offsets(...) -> (offset_x, offset_y)`
Zoom mantendo sob o cursor o mesmo ponto da imagem:
```
origin_x, origin_y = preview_view_rect(stage_w, stage_h, drawn_w, drawn_h, off_x, off_y)
ratio_x = (cursor_x - origin_x) / max(1, drawn_w)
ratio_y = (cursor_y - origin_y) / max(1, drawn_h)
return cursor_x - ratio_x * zoomed_w, cursor_y - ratio_y * zoomed_h
```
Exemplo medido: `preview_zoom_offsets(983, 553, 982, 552, 0.0, 0.0, 1228, 690, 491, 276) = (-123.0, -69.0)`.
O resultado sempre passa por `preview_clamped_offset` em seguida (pode encostar na borda, nunca passar dela).

### 3.9 `preview_fraction_from_view(x, y, drawn_w, drawn_h, origin_x, origin_y) -> (fx, fy)`
Inversa de `to_view`:
```
fracao_x = (x - origin_x) / max(1, int(drawn_w))
fracao_y = (y - origin_y) / max(1, int(drawn_h))
return min(1.0, max(0.0, fracao_x)), min(1.0, max(0.0, fracao_y))   # limitada ao vídeo
```
Round-trip `to_view → fraction` é exato dentro de ~1e-3 (testado com 200 casos aleatórios).

### 3.10 `_preview_frame_transform(canvas)` (método, linha ~1642)
`(origin_x, origin_y, max(1, drawn_w), max(1, drawn_h))` — referencial único de TODA a seleção (desenho e crop). Se o viewport não existe → `(0.0, 0.0, 1, 1)`.

### 3.11 Enquadramento do palco na aba (método `_preview_available_box`, linha ~1450)
- `available_width = parent.winfo_width() - PREVIEW_STAGE_MARGIN * 2` (menos 16 px).
- `reserved` = soma das alturas PEDIDAS (`winfo_reqheight`) de todos os filhos da aba, exceto o holder do palco, mais os `pady` de pack de cada um (`vertical_padding_total`); soma ainda o padding vertical interno da aba (`frame_vertical_padding`; fallback 16 se a leitura falhar).
- `available_height = altura_visível_do_painel (ffmpeg_scroll_canvas.winfo_height()) - reserved - PREVIEW_STAGE_MARGIN * 2`, clampado em `[135, 760]` (`PREVIEW_STAGE_MIN_HEIGHT`/`PREVIEW_STAGE_MAX_HEIGHT`).
- Retorna `(max(240, available_width), available_height)`. A conta NÃO depende da altura do palco (evita laço de reencaixe com o Tk).
- `_fit_preview_stage` roda em `<Configure>` do canvas e da aba, no `after_idle` da criação e ao trocar de aba; se `(width,height)` mudou: `holder.configure(width, height)`, resize do player nativo, repaint e `_schedule_preview_restart`.

### 3.12 Proporções exibidas por ferramenta
- Girar (`_rotated_media_size(media, filters)`): se `"transpose=1"` ou `"transpose=2"` ∈ filters → `(media.height, media.width)`; senão `(media.width, media.height)`.
- Cortar (`_cut_display_size(media)`): se `media.rotation % 180 != 0` → `(media.height, media.width)`; senão `(media.width, media.height)` (o FFmpeg autorrota antes dos filtros).
- O viewport é reinicializado com esse tamanho em `_activate_preview` (via `_reset_preview_view`).

---

## 4. Seleção: desenho, alças, limites, limpeza

### 4.1 Desenho (`_draw_preview_selection`, linha ~1668)
- Retângulo com `outline="#ffd700"`, `width=1`, `tags="preview_selection"`, sobre o retângulo de `to_view(...)`.
- 9 alças de `4x4 px` desenhadas centradas (`centro ± size/2`) em todas as combinações de `{left, (left+right)/2, right} × {top, (top+bottom)/2, bottom}`, `fill="#ffd700"`, `outline="#ffd700"`, `width=0`, mesma tag (a alça central é desenhada mas não é um "handle" funcional separado: o centro responde como `"move"`).
- A seleção é desenhada SEMPRE por último, por cima da imagem do quadro.

### 4.2 Alças e hit-test (`selection_handle_at(rect, x, y, tolerance=PREVIEW_SELECTION_HANDLE=7)`)
Precedência exata: **cantos (nw/ne/sw/se) → n/s → w/e → move**. Fora de `left-7 ≤ x ≤ right+7` e `top-7 ≤ y ≤ bottom+7` → `None`.
- `"nw"`: |x-left|≤7 e |y-top|≤7; `"ne"`: |x-right|≤7 e |y-top|≤7; `"sw"`: |x-left|≤7 e |y-bottom|≤7; `"se"`: |x-right|≤7 e |y-bottom|≤7;
- `"n"`/`"s"`: |y-top|≤7 / |y-bottom|≤7 (dentro da faixa em x);
- `"w"`/`"e"`: |x-left|≤7 / |x-right|≤7 (dentro da faixa em y);
- `"move"`: `left ≤ x ≤ right and top ≤ y ≤ bottom` (estritamente dentro do retângulo).
Exemplos de teste com `rect=(100, 50, 300, 200)`: `nw=(100,50)`, `ne=(300,50)`, `sw=(100,200)`, `se=(300,200)`, `n=(200,50)`, `s=(200,200)`, `w=(100,125)`, `e=(300,125)`, `move=(200,125)`; `(1000,1000)` e `(200,400)` → `None`.

### 4.3 Criação por arrasto (`selection_from_drag(start, end, min_fx, min_fy)`)
- `left,right = sorted((start[0], end[0]))`; `top,bottom = sorted(...)` (funciona em qualquer direção).
- Cada valor clampado a `0..1`.
- Se `right-left < min_fx` ou `bottom-top < min_fy` → retorna `None` (não cria seleção).
- Durante o arrasto (modo `"draw"`), o mínimo passado é `0.0, 0.0` (pode desenhar pequeno); o mínimo real só é aplicado ao SOLTAR, em frações: `min_fx = PREVIEW_SELECTION_MIN_SIZE / drawn_w`, `min_fy = PREVIEW_SELECTION_MIN_SIZE / drawn_h` (8 px / tamanho desenhado). Se ao soltar ficar pequeno demais → `None` → restaura a seleção anterior (`drag["previous"]`).

### 4.4 Redimensionar (`selection_resized(selection, handle, fx, fy, min_fx, min_fy)`)
Handle é string contendo os caracteres; regras (`fx`/`fy` clampados a 0..1 antes):
```
if "w" in handle: left   = min(fracao_x, right  - min_fx)
if "e" in handle: right  = max(fracao_x, left   + min_fx)
if "n" in handle: top    = min(fracao_y, bottom - min_fy)
if "s" in handle: bottom = max(fracao_y, top    + min_fy)
```
Depois clampa todos os lados a `0..1` (não inverte nem zera; respeita o mínimo de 8 px).
Exemplos: `selection_resized(PreviewSelection(0.2,0.2,0.8,0.8), "w", 0.5, 0.0, 0.01, 0.01)` → left=0.5, right=0.8; `"ne"` com (0.95, 0.05) → right=0.95, top=0.05, left=0.2, bottom=0.8.

### 4.5 Mover (`selection_moved(selection, dx, dy)`)
```
left = min(max(0.0, selection.left + dx), 1.0 - largura)
top  = min(max(0.0, selection.top  + dy), 1.0 - altura)
→ PreviewSelection(left, top, left + largura, top + altura)
```
Nunca sai do quadro e mantém o tamanho.

### 4.6 Crop em pixels (`selection_crop_pixels(selection, video_width, video_height)`)
`None` se `video_width <= 0` ou `video_height <= 0` ou se `width/height` da seleção ≤ 0. Caso contrário:
```
x0 = round(left   * video_width);  y0 = round(top    * video_height)
x1 = round(right  * video_width);  y1 = round(bottom * video_height)
x0 = clamp(x0, 0, max(0, video_width  - 2));  y0 = clamp(y0, 0, max(0, video_height - 2))
x1 = clamp(x1, x0 + 2, video_width);          y1 = clamp(y1, y0 + 2, video_height)
largura = (x1 - x0) - (x1 - x0) % 2   →  max(2, ...)   # PAR
altura  = (y1 - y0) - (y1 - y0) % 2   →  max(2, ...)   # PAR
return x0, y0, largura, altura
```
Exemplos: seleção (0.25,0.25,0.75,0.75) em 1920x1080 → `(480, 270, 960, 540)`; em qualquer caso `x+largura ≤ video_width`, `y+altura ≤ video_height`, largura/altura pares e ≥ 2.

### 4.7 Filtro de crop (`selection_crop_filter(crop)`)
```
f"crop={largura}:{altura}:{x}:{y}"
```
Exemplo: `selection_crop_filter((10, 20, 100, 50)) == "crop=100:50:10:20"`.
Ou seja: `crop=LARGURA:ALTURA:X:Y`.

### 4.8 Limpar / desfazer
- `_clear_preview_selection(canvas)`: apaga seleção+drag+filtros lembrados e `canvas.delete("preview_selection")`.
- Menu de contexto (botão direito, clique parado sobre a seleção): menu Tk `tearoff=False` com UM item, label exato `"Desfazer seleção"`, que chama `_clear_preview_selection(canvas)`; aberto via `tk_popup(x_root, y_root)`.
- O menu só abre se o ponto do PRESS estiver dentro do retângulo da seleção (`left ≤ ponto_x ≤ right and top ≤ ponto_y ≤ bottom`); fora disso ou sem seleção → nada.

---

## 5. Interação (mouse) — bindings exatos

Todos aplicados no canvas do palco em `_create_preview_stage` (linha ~1391):

| Binding exato | Handler | Comportamento |
|---|---|---|
| `"<MouseWheel>"` | `_preview_wheel` | zoom; retorna `"break"` (nunca rola o painel) |
| `"<Button-4>"` / `"<Button-5>"` | `_preview_wheel` | idem (roda em X11/Linux: 4 = cima, 5 = baixo) |
| `"<ButtonPress-1>"` | `_preview_press` → `_preview_pan_start` | botão ESQUERDO inicia o arrasto (pan) do quadro |
| `"<B1-Motion>"` | `_preview_motion` → `_preview_pan_move` | arrasta o quadro (pan) |
| `"<ButtonRelease-1>"` | `_preview_release` → `_preview_pan_end` | termina o pan |
| `"<ButtonPress-2>"` | `_preview_pan_start` | botão do MEIO também inicia pan |
| `"<B2-Motion>"` | `_preview_pan_move` | pan com o meio |
| `"<ButtonRelease-2>"` | `_preview_pan_end` | termina o pan |
| `"<ButtonPress-3>"` | `_preview_select_press` | botão DIREITO: desenha / pega alça / move a seleção |
| `"<B3-Motion>"` | `_preview_select_motion` | atualiza desenho/resize/move |
| `"<ButtonRelease-3>"` | `_preview_select_release` | finaliza; clique parado abre o menu |
| `"<Motion>"` | `_preview_hover` | feedback de cursor (alças/mãozinha/crosshair) |
| `"<Configure>"` (canvas e parent com `add="+"`) | `_fit_preview_stage` | reencaixa o palco |

Regra de ouro: **esquerdo arrasta o vídeo, direito seleciona a área** (o teste `test_left_button_pans_and_right_draws` garante isso e que não existe a antiga checagem de state `0x0001` em `_preview_press`).

### 5.1 Pan (esquerdo/meio)
- `_preview_pan_start`: grava `drag_origin=(int(x), int(y))` e `drag_offsets=(offset_x, offset_y)`; se `_preview_can_pan(view)` (quadro desenhado maior que o palco em qualquer eixo) → cursor `"fleur"`.
- `_preview_pan_move`: se não pode pan → nada; senão `offset = preview_clamped_offset(stage, drawn, drag_offsets + (event - origin))` por eixo e `_preview_move_item(canvas)` (só `canvas.coords` — SEM repintar a imagem).
- `_preview_pan_end`: `drag_origin = None`, cursor `"crosshair"`.
- Sem zoom (quadro ≤ palco) o quadro fica centralizado e o pan é no-op.

### 5.2 Roda do mouse / zoom no cursor (`_preview_wheel` + `_preview_zoom_at`)
- `_preview_wheel_steps(event)`: se `event.num` for `4.0` → `+1.0`; `5.0` → `-1.0`; senão `delta / 120.0` (campos ausentes do Tk vêm como `"??"` e viram 0.0 — nunca levanta exceção). Ex.: `delta=120 → 1.0`, `delta=-240 → -2.0`, `num=4 → 1.0`, `num="??", delta="??" → 0.0`.
- `_preview_wheel(canvas, event)`: guarda `view is None` / `steps == 0` / sem mídia (`not preview_stills and not preview_frames`) → `"break"`; senão `_preview_zoom_at(canvas, event.x, event.y, PREVIEW_ZOOM_STEP ** steps)` e `"break"`. Sempre `"break"` (a roda nunca chega ao scroll do painel).
- `_preview_zoom_at`:
  1. calcula `drawn` (tamanho atual), `zoomed = preview_zoom_clamped(view.zoom * factor)` e o novo `preview_drawn_size(stage, stage, zoomed) → (zoomed_w, zoomed_h, effective)`;
  2. se `(zoomed_w, zoomed_h) == drawn` → return (nada muda; ex.: já em 5x);
  3. offsets = `preview_zoom_offsets(...)` (mantém sob o cursor o mesmo ponto da imagem) e clampados;
  4. `view.zoom = effective` (ATENÇÃO: grava o zoom efetivo pós-orçamento, não o pedido), `view.offset_x/y` clampados;
  5. `_paint_preview_view(canvas)` (redesenha quadro + seleção) e `_schedule_preview_restart(canvas)`.
- Zoom de uma roda para cima: 1.0 → 1.25 (fator 1.25); para baixo: para no 1.0 (vídeo inteiro).

### 5.3 Botão direito — máquina de estados (`pending` → `draw` | `resize` | `move`)
`_preview_select_press`:
- Se existe seleção e o ponto está sobre um handle:
  - `handle == "move"` → `preview_selection_drag[canvas] = {"mode": "move", "selection": <atual>, "start": fração_do_ponto, "press": (x, y)}`;
  - outro handle → `{"mode": "resize", "handle": handle, "selection": <atual>, "press": (x, y)}`.
- Senão → `{"mode": "pending", "start": fração, "press": (x, y), "previous": seleção_atual_ou_None}` (clique parado no vazio NÃO cria seleção).
`_preview_select_motion`:
- `pending`: se `abs(dx) < 4 AND abs(dy) < 4` (`PREVIEW_SELECTION_DRAG_THRESHOLD`) → ignora; ao passar de 4 px em algum eixo vira `"draw"` e descarta a seleção anterior;
- `draw`: `selection_from_drag(start, fração_atual, 0.0, 0.0)` (o mínimo é 0 durante o arrasto);
- `resize`: `selection_resized(drag["selection"], handle, fração_atual, min_fx, min_fy)` com mínimos de 8 px em frações;
- `move`: `selection_moved(drag["selection"], agora - start)`;
- sempre termina com `_redraw_preview_selection`.
`_preview_select_release`:
- `pending` → clique parado → tenta abrir o menu `"Desfazer seleção"`;
- `move` → se o movimento total < 4 px em ambos os eixos → menu; senão só redesenha (e **não** atualiza os filtros lembrados — ver §8);
- `draw` → aplica o mínimo de 8 px/frações; se o resultado for `None`, restaura `drag["previous"]`;
- se há seleção → `_preview_remember_selection_filters(canvas)` e `_redraw_preview_selection`.
Todos retornam `"break"`.

### 5.4 Hover (`_preview_hover`)
Sem seleção → cursor `"crosshair"`. Com seleção → cursor = `PREVIEW_SELECTION_CURSORS.get(handle_detectado_ou_"" , "crosshair")` (alças: `sb_v_double_arrow` / `sb_h_double_arrow` / `sizing`; dentro: `hand2`).

---

## 6. Redesenho da seleção (quando acontece)

Regras extraídas de `_paint_preview_view`, `_preview_move_item` e `_preview_zoom_at`:
- **`_paint_preview_view`** (linha ~1506): pega `preview_frames` (vivo) ou `preview_stills` (congelado); monta a imagem no tamanho desenhado; `canvas.delete("all")` (derruba tudo, inclusive a seleção antiga); cria a imagem em `(x, y)` de `preview_view_rect` com `anchor="nw"`; e por último chama `_draw_preview_selection` — a seleção é sempre reinserida sobre os pixels escolhidos com o zoom/deslocamento atuais. Sai cedo se não há estágio/source.
- **`_preview_move_item`** (linha ~1532): apenas `canvas.coords(item, x, y)` e `_redraw_preview_selection` (apaga tag e redesenha) — o arrasto do quadro NÃO repinta a imagem.
- **`_preview_zoom_at`**: muda zoom/offsets e chama `_paint_preview_view` (que redesenha a seleção) + `_schedule_preview_restart`.
- **Motion do botão direito**: cada movimento de draw/resize/move chama `_redraw_preview_selection`; releases idem.
- **`_fit_preview_stage`**: se o tamanho mudou (ou não há item de quadro) → `_paint_preview_view` (seleção acompanha o novo enquadramento, pois está em frações).
- **Quadros vivos** (`_render_canvas_frame`): cada quadro novo faz `preview_frames[canvas] = image` + `_paint_preview_view` a 33 ms (`_poll_preview_frames`).
- Consequência: zoom, pan, resize da janela, novo quadro e nova mídia SEMPRE resultam na seleção exatamente sobre os mesmos pixels do vídeo.
- Reinício do pipeline após enquadramento/zoom: `_schedule_preview_restart` agenda (debounce `PREVIEW_ZOOM_RESTART_MS = 250` ms) um `_restart_canvas_preview` que fecha e reabre o FFmpeg na nova resolução mantendo a posição ao vivo; se a posição ≥ `timeline.end - 0.02` volta para `timeline.start`. Só age quando há prévia tocando naquele canvas e não é áudio puro.

---

## 7. Velocidades

- `PREVIEW_SPEED_VALUES = (0.25, 0.5, 1.0, 2.0, 4.0)`; estado inicial `preview_speed = 1.0` e label `preview_speed_var = "1.0x"`.
- `_change_preview_speed(direction)` (linha ~1975):
  ```
  index  = índice de PREVIEW_SPEED_VALUES mais próximo do preview_speed atual
  speed  = values[max(0, min(len(values)-1, index + direction))]   # satura nos limites
  preview_speed_var.set(f"{preview_speed:g}x")                      # "0.25x", "0.5x", "1x", "2x", "4x"
  if preview_playing: _toggle_preview(); _toggle_preview()          # reinicia a reprodução na nova velocidade
  ```
- Saturação: acima de 4.0 fica em 4.0; abaixo de 0.25 fica em 0.25 (testes: 1.0 → 2.0 → 4.0 → 4.0; depois 4x de "menos" chega a 0.25 e a quinta não muda).
- Botões (comuns a áudio e vídeo, `_add_preview_speed_controls`): "slower" (58x48, `padx=(0,18)`, tooltip `"Diminuir velocidade"`), "play" (76x60, tooltip `"Reproduzir ou pausar"`), "faster" (58x48, `padx=(18,0)`, tooltip `"Aumentar velocidade"`); label da velocidade centralizado abaixo.
- Pipeline de reprodução (contexto para o port):
  - áudio: `ffplay` com `-af` = cadeia atempo: enquanto `target > 2.0` empilha `2.0`; enquanto `< 0.5` empilha `0.5`; fator final no fim; `",".join(f"atempo={factor:.6g}")` (ex.: 4x → `atempo=2,atempo=2`; 0.25x → `atempo=0.5,atempo=0.5`). Duração de mídia do `-t` não é dividida pela velocidade.
  - vídeo: quadros RAW (`-f rawvideo -pix_fmt rgb24`) a **15 fps** com filtros `setpts=PTS/{speed}`, `fps=15`, `scale={width}:{height}:force_original_aspect_ratio=decrease`, `pad={width}:{height}:(ow-iw)/2:(oh-ih)/2`, `setsar=1`; resolução = `_preview_pipeline_size(canvas)` = `preview_render_size(drawn, media)` (fallback `(max(320, w), max(180, h))` sem viewport).
  - com velocidade ≠ 1.0 ou zoom/deslocamento **não** no enquadramento cheio (`_preview_view_is_fitted`: |zoom-1| < 1e-6 e |offsets| < 0.5) o player nativo (MCI) é ignorado e usa-se o pipeline de quadros.

---

## 8. Rotação / espelhamento da seleção

### 8.1 Átomos válidos (`selection_filter_atoms(filters)`)
Quebra a string por `,`, faz `strip` e mantém, NA ORDEM, apenas: `"transpose=1"`, `"transpose=2"`, `"hflip"`, `"vflip"`. Ex.: `"transpose=1,hflip" → ["transpose=1","hflip"]`; `""` e `"null"` → `[]`.
Fonte dos filtros no app (aba Girar, `_rotate_preview_filter`): `degrees == -90 → "transpose=2"`; `degrees == 90 → "transpose=1"`; `abs(degrees) == 180 → "hflip,vflip"`; depois, na ordem, `hflip` se marcado e `vflip` se marcado; juntados por `,` (pode ser string vazia).

### 8.2 Reexpressão por átomo (`selection_after_filter_atom(selection, atom)`)
Fórmulas exatas (giro em múltiplos de 90° e espelhamentos levam retângulo alinhado em retângulo alinhado):
```
hflip       → (1.0 - right, top,        1.0 - left,  bottom)
vflip       → (left,        1.0 - bottom, right,     1.0 - top)
transpose=1 → (1.0 - bottom, left,       1.0 - top,    right)
transpose=2 → (top,         1.0 - right,  bottom,      1.0 - left)
```
Átomo desconhecido → devolve a seleção inalterada.

### 8.3 Troca de filtros (`selection_between_filters(selection, old_filters, new_filters)`)
Desfaz o giro antigo (átomos em ordem INVERSA, cada um invertido: `hflip↔hflip`, `vflip↔vflip`, `transpose=1↔transpose=2`) e depois aplica os novos na ordem. Resultado: a MESMA região da imagem original na nova orientação exibida. Round-trip ida/volta restaura a seleção com precisão 1e-6 (testado com 200 casos × 5 combinações).

### 8.4 Gancho no app (`_rotate_selection_with_filters`, linha ~3686)
- Só age no canvas `self.rotate_preview`; sem seleção → return.
- `antigos = preview_selection_filters.get(canvas, "")`; `novos = self._rotate_preview_filter()`; se `antigos == novos` → return.
- Senão: `preview_selections[canvas] = selection_between_filters(selecao, antigos, novos)` e `preview_selection_filters[canvas] = novos`.
- Chamado por `_refresh_rotate_thumbnail` (linha ~3673) na ordem: `_stop_preview()` → `_rotate_selection_with_filters()` → `_apply_rotate_media_size()` (troca a proporção do palco quando entra/sai transposição) → `_show_video_thumbnail(..., self._rotate_preview_filter())`. `_refresh_rotate_thumbnail` é chamado pelo combo de giro (`<<ComboboxSelected>>`), pelos checkbuttons "Espelhar horizontal"/"Espelhar vertical" (via `_on_rotate_transform_changed`) e por `_update_rotate_control_state`.

### 8.5 Quando `preview_selection_filters` é lembrado/esquecido
- Lembrado: ao SOLTAR o botão direito nos modos `"draw"` e `"resize"`, se existe seleção (`_preview_remember_selection_filters` → `preview_selection_filters[canvas] = _preview_selection_filters_for(canvas)`).
- `_preview_selection_filters_for(canvas)`: se `canvas is self.rotate_preview` → `self._rotate_preview_filter()`; senão → `""` (a aba Cortar não tem filtros).
- Esquecido: junto com a seleção, em `_clear_preview_selection` (`preview_selection_filters.pop`).
- Nuance comprovada no código: no modo `"move"`, quando o arrasto foi real, o release redesenha e retorna SEM chamar `_preview_remember_selection_filters`; cliques parados no modo move também não lembram (abrem o menu). Portanto, se a seleção for movida e depois o giro mudar, `selection_between_filters` parte dos filtros lembrados do último draw/resize.

---

## 9. Confirmação ao Executar (`_confirm_preview_selection`, linha ~4117)

- Assinatura: `_confirm_preview_selection(tool: str) -> bool`. Chamada por `run_current_tool` (linha ~4156) ANTES de capturar as opções do worker; se retornar `False`, a execução NÃO acontece (return).
- Só age quando `tool` é exatamente `"Cortar"` ou `"Girar vídeo"`. Outras ferramentas (`"Extrair áudio"`, `"Juntar áudios/vídeos"`, `"Inserir áudio"`, `"Limpar áudio"`) → `True` sem diálogo.
- O crop vem de `_selection_crops()[ "cut_crop" | "rotate_crop" ]`; sem seleção (`None`) → `True` sem diálogo.
- Com seleção, monta a mensagem EXATA:
  ```
  Será salvo apenas o que está DENTRO da seleção: {largura} x {altura} pixels, a partir de ({x}, {y}).
  O restante do quadro será descartado.{extra}
  ```
  (a primeira linha termina com ponto; `\n` entre as duas frases; `largura`/`altura`/`x`/`y` em pixels do vídeo).
- `extra` por ferramenta:
  - `"Cortar"` com modo `"Sem Reencode"` (`_cut_mode_is_copy()` = `str(cut_mode).startswith("Sem Reencode")`): ANTES do diálogo o app faz `cut_mode_var.set("Reencode Completo")` + `_update_cut_controls()`, e acrescenta:
    `"\n\nA seleção exige reencodar: o modo foi alterado para 'Reencode Completo'."`
  - `"Girar vídeo"` com `rotate_metadata_var.get()` verdadeiro OU `not self._rotate_preview_filter()` (nenhum filtro de imagem): acrescenta:
    `"\n\nA seleção exige reencodar: o arquivo será regerado."`
- Diálogo: `messagebox.askokcancel("sig", message)` (título `"sig"`). OK → `True` (executa); Cancelar → `False` (não executa; nada é alterado além do que já foi alterado no modo de corte).
- Testes: cancelar NÃO executa e a mensagem contém a resolução (`"960 x 540"` para seleção 0.25-0.75 em 1920x1080) e `"desc"` (de "descartado"); OK mantém o modo e retorna True; sem seleção não há aviso; outras ferramentas não são afetadas.

---

## 10. Decisão de crop por ferramenta (como chega ao worker)

### 10.1 Captura na UI thread (`_selection_crops`, linha ~4104; `run_current_tool`)
```
crops = {"cut_crop": None, "rotate_crop": None}
cut_media    = self.cut_media_profile     # se has_video: w,h = _cut_display_size(cut_media)
               crops["cut_crop"]    = _preview_selection_crop(self.cut_preview, w, h)
rotate_media = self.rotate_media_profile  # se has_video: w,h = _rotated_media_size(rotate_media, _rotate_preview_filter())
               crops["rotate_crop"] = _preview_selection_crop(self.rotate_preview, w, h)
```
- `_preview_selection_crop(canvas, video_width, video_height)` = `selection_crop_pixels(seleção_do_canvas, w, h)` (ou `None` sem seleção).
- `run_current_tool` grava em `self.worker_options`: `"cut_crop": selection_crops["cut_crop"]`, `"rotate_crop": selection_crops["rotate_crop"]` — tuplas `(x, y, largura, altura)` em pixels, capturadas na thread da UI (workers usam só valores simples).
- O palco da aba Extrair áudio (e o fluxo de Inserir áudio) também permite desenhar/desfazer seleção (mesmos bindings), mas NENHUM worker consome essa seleção: só `cut_crop` e `rotate_crop` são lidos.
- `_worker_crop(key)`: lê `worker_options[key]`; se vazio/inválido → `None`; exige `largura ≥ 2 e altura ≥ 2`, senão `None`.

### 10.2 Ferramenta Cortar (`_cut_worker`, linha ~4690)
- `crop = self._worker_crop("cut_crop")`.
- Se `crop and fast_copy` (modo Sem Reencode): log `"A seleção de área exige reencodar: usando o Reencode Completo."` e força `fast_copy = False; smart_cut = False`.
- Se `crop and smart_cut` (SmartCut): log `"A seleção de área exige reencodar todo o trecho: usando o Reencode Completo."` e força `smart_cut = False`.
- O crop só é APLICADO no caminho de reencode completo: `_cut_video_precise(..., crop=crop)`:
  - log (se crop): `f"Recorte por seleção: {crop[2]} x {crop[3]} pixels a partir de ({crop[0]}, {crop[1]})."`
  - `plan = selection_crop_filter(crop) if crop else "null"` → `-vf "crop=W:H:X:Y"` (ou `-vf "null"` sem seleção — obrigatório para o filtro existir no caminho de reencode);
  - `input_args, filter_args = self._filter_for_profile(plan, profile)`; o comando usa `-map 0:v:0? -map 0:a? -sn -dn *filter_args ... -movflags +faststart`.
- Caminhos de cópia/SmartCut nunca recebem crop (por construção acima).

### 10.3 Ferramenta Girar (`_rotate_worker`, linha ~5140)
- `crop = self._worker_crop("rotate_crop")`; se crop → mesmo log de recorte.
- Se `crop and metadata_mode` (somente metadados): log `"A seleção de área exige reencodar: o modo somente metadados não será usado."` e `metadata_mode = False`.
- Filtros montados na ordem: `transpose=2` (-90) / `transpose=1` (90) / `hflip,vflip` (±180) → `hflip` (se marcado) → `vflip` (se marcado) → **`crop=W:H:X:Y` por ÚLTIMO** (depois da rotação). `filter_text = ",".join(filters)`.
- Se `filters` ficar vazio (sem giro, sem espelho e sem crop) → caminho cópia `-c copy`; com crop, `filters` nunca fica vazio → reencode.
- `input_args, filter_args = self._filter_for_profile(filter_text, profile)` no caminho de reencode (e igual no processamento paralelo, `_rotate_video_parallel` → mesmo `_filter_for_profile(filters, profile)`).
- Testes: `transpose=1,crop=200:100:5:5` "transpose=1,crop=200:100:5:5" no `-vf`; com seleção o modo metadados não é usado (`_execute` não chamado).

### 10.4 `_filter_for_profile(filters, profile)` (linha ~4487)
```
if profile.key == "vaapi":
    return ["-vaapi_device", "/dev/dri/renderD128"], ["-vf", f"{filters},format=nv12,hwupload"]
return [], ["-vf", filters]
```
Ou seja: no caminho CPU/NVENC/QSV/AMF o filtro entra como `-vf <cadeia>` onde `<cadeia>` começa/termina com o crop conforme a ferramenta.

---

## 11. Ganchos de teste existentes (o que os testes travam)

### 11.1 `tests/test_ffmpeg_preview_stage.py` (palco/player)
- `preview_stage_size`: encosta na LARGURA quando cabe `(1200,1000,1920,1080)→(1200,675)`; encosta na ALTURA quando a largura não cabe `(1200,400,1920,1080)→(711,400)`; retrato `(1000,800,1080,1920)→(450,800)`; sem mídia = 16:9 `(1200,1000,0,0)→(1200,675)`; 400 casos aleatórios: nunca passa da caixa e encosta numa borda (exceção: vence o mínimo 240x135, fazendo a aba rolar); caixa degenerada `(0,0,...)→(240,135)`; proporção preservada com delta 0.02.
- `preview_drawn_size`: fit `(983,553,1.0)→(982,552)` e zoom 1.0; passo da roda `(983,553,1.25)→(1228,690)`; sempre par e ≥ 2 (200 casos); clamp 1.0..5.0 (palco pequeno: orçamento não interfere); palco grande (1400x788 em 5x): largura×altura < 3900000×1.02 e zoom efetivo < 5.0.
- `preview_render_size`: nunca acima de 1440000 px `(3000,1687)→(1600,900)`; nunca acima da fonte `(1200,675,640,360)→(640,360)`; acompanha o desenhado no zoom `(1228,690,1280,720)→(1228,690)`.
- `preview_clamped_offset`: `(983,1228,-9999)→-245`, `(983,1228,50)→0`, `(983,500,-30)→0`; intervalo sempre `[-245, 0]`.
- `preview_view_rect`: centraliza menor `(983,553,786,442,0,0)→(98,55)`; ampliado usa offset clampado `(983,553,1228,690,-9999,-9999)→(-245,-137)`.
- `preview_zoom_offsets`: caso real `(983,553,982,552,0,0,1228,690,491,276)→(-123,-69)`; 300 casos: o ponto sob o cursor nunca passa de 0.06 de fração do desvio (com clamp pode encostar na borda).
- `_preview_wheel_steps`: `delta=120→1`, `-120→-1`, `-240→-2`; `num=4→1`, `num=5→-1`; `num`/`delta` `"??"`/`None`/vazios → 0 (sem exception).
- `_rotated_media_size`: transposição troca w/h (1920x1080→1080x1920); `hflip`/vazio mantêm.
- `parse_tk_padding`/`vertical_padding_total`/`frame_vertical_padding`: toleram `"<pixel object: '12'>"`, `"{4 6}"`, tuplas simples; e `_preview_available_box` desconta esses paddings e NÃO depende da altura do palco.
- Wiring: `pack_propagate(False)` no holder; `holder.configure` no fit; bindings `<MouseWheel>`, `<ButtonPress-1>`, `<B1-Motion>`, `<ButtonRelease-1>` (e `<Button-2>`/`<Button-3>`/`<B3-Motion>`); `_preview_wheel` usa `_preview_zoom_at` e retorna `"break"`; `_preview_pan_move` usa `_preview_move_item`/`_preview_can_pan`; `_preview_available_box` usa `winfo_children`/`winfo_reqheight`/`ffmpeg_scroll_canvas`; `_start_canvas_preview` usa `_preview_pipeline_size(canvas)`; `_show_video_thumbnail` guarda em `preview_stills[canvas]` sem `.thumbnail()`; `_render_canvas_frame` usa `preview_frames[canvas]`; `_toggle_preview` consulta `_preview_view_is_fitted`; `_activate_preview` usa `_reset_preview_view`/`_rotated_media_size`; `_schedule_preview_restart` usa `PREVIEW_ZOOM_RESTART_MS`/`_restart_canvas_preview`; `_create_preview_stage` aparece 3× no arquivo (um por palco: Cortar, Extrair, Girar).

### 11.2 `tests/test_ffmpeg_area_selection.py` (seleção/player)
- `selection_from_drag`: normaliza para retângulo ordenado; cantos em qualquer direção; clampa ao vídeo (`(-0.5,-0.2)→(0,0)`, `(1.8,2.0)→(1,1)`); arrasto pequeno → `None`.
- `selection_handle_at`: 9 casos de alças/cantos/move; fora → `None`.
- `selection_resized`: lado oeste move só a borda oeste; canto ne; ao apertar além do limite não inverte e respeita mínimo (≥ 0.05 no teste); canto fora do quadro clampa em 1.0.
- `selection_moved`: dx/dy gigantes prendem nas bordas mantendo o tamanho.
- `to_view`: 800x400 sem offset e 1600x800 com offset -100/-50 (seleção anda junto).
- ida/volta `to_view ↔ preview_fraction_from_view`: 200 casos aleatórios com erro < 1e-3.
- `selection_crop_pixels`: paridade e limites; 200 casos aleatórios: `x≥0`, `y≥0`, `largura/altura ≥ 2` e pares, `x+largura ≤ 640`, `y+altura ≤ 360`.
- `selection_crop_filter`: `(10,20,100,50) → "crop=100:50:10:20"`.
- Wiring: bindings `<ButtonPress-1>`, `<B1-Motion>`, `<ButtonRelease-1>`, `<ButtonPress-2>` em `_create_preview_stage` (+ `<ButtonPress-3>`, `<B3-Motion>`, `<ButtonRelease-3>`); `_draw_preview_selection` usa `PREVIEW_SELECTION_OUTLINE` e `width=PREVIEW_SELECTION_WIDTH`; `_paint_preview_view` chama `_draw_preview_selection`; `_preview_move_item` chama `_redraw_preview_selection`; `_preview_zoom_at` chama `_paint_preview_view`; menu com `"Desfazer seleção"` + `_clear_preview_selection` e só em clique parado (`_preview_select_release` chama `_preview_open_selection_menu`); `_reset_preview_view` e `_preview_show_hint` chamam `_clear_preview_selection`; `_preview_select_motion` usa `"draw"/"resize"/"move"` com `selection_from_drag`/`selection_resized`/`selection_moved`; `_preview_press`/`_preview_motion`/`_preview_release` delegam ao pan e `_preview_select_press` usa `selection_handle_at`; `_preview_press` NÃO contém `0x0001`.
- Velocidades: lista exata de 5 valores; passeio saturando (1→2→4→4; depois 0.25 e não desce); label `"2x"`/`"0.25x"`.
- Zoom: saída para no 1.0 (`preview_drawn_size(800,450,0.2)` → `(800,450,1.0)`); entrada vai a 5.0 (`(400,240,5.0)→(2000,1200)`).
- Títulos/subtítulos das abas removidos (não recriar).
- Rotação: `selection_filter_atoms` em ordem e ignorando `null`; mapeamentos unitários exatos (hflip/vflip/transpose=1/transpose=2 do retângulo (0.1,0.2,0.4,0.6)); 200 casos ida/volta com 5 combinações restaurando com 1e-6; crop sobre os mesmos pixels após girar (1920x1080, seleção (0.25,0.10,0.75,0.30): antes `(480,108,960,216)`, depois de `transpose=1` no quadro girado `(756,480,216,960)`); 180° = hflip+vflip leva ao canto oposto; `_refresh_rotate_thumbnail` chama `_rotate_selection_with_filters` que usa `selection_between_filters`/`preview_selection_filters`; `_preview_select_release` chama `_preview_remember_selection_filters`; `_clear_preview_selection` faz `preview_selection_filters.pop`.
- Worker: Cortar com crop → `-vf "crop=640:360:100:50"` e log "Recorte por seleção"; sem seleção → `-vf "null"`; com seleção o modo Sem Reencode é forçado a preciso (não copia streams); Girar com crop e modo metadados → reencode com `-vf "transpose=1,crop=200:100:5:5"`.
- Confirmação: cancelar não executa; mensagem com `"960 x 540"` e `"desc"`; OK mantém modo; modo Sem Reencode é trocado por Reencode Completo (+ `_update_cut_controls` 1×) e a mensagem contém `"Reencode"`; sem seleção não avisa; outras ferramentas não avisam; `run_current_tool` chama `_confirm_preview_selection` e contém `cut_crop`/`rotate_crop`; `PREVIEW_SELECTION_MIN_SIZE` existe no módulo.

---

## 12. Notas para o port / não determinado no código

- Nada ficou indefinido nas regiões pedidas; todos os comportamentos acima estão no código e/ou nos testes.
- Cosmético/não especificado no código (fica a critério do port): aparência/FONTE do menu de contexto Tk (é o menu padrão do sistema, `tearoff=False`) e a arte dos cursores (`crosshair`, `fleur`, `sb_*`, `sizing`, `hand2` são cursores do SO).
- O palco é um widget empacotado com altura PRÓPRIA (holder com `pack_propagate(False)` + `pady=(0, 6)`), ancorado ao centro: nunca fica sobre os controles da ferramenta; a aba rola quando o mínimo 240x135 não cabe.
- O pipeline de quadros do Windows (FFmpeg rawvideo + PIL + `PhotoImage`, 15 fps) é detalhe de implementação; no Android o equivalente é decodificar o quadro no tamanho `preview_render_size` e desenhar com o mesmo transform (zoom/offset/frações) para a seleção continuar sobre os mesmos pixels.
- A seleção é por canvas (uma por palco). Cortar e Girar têm palcos/estados separados; Extrair usa o mesmo mecanismo porém sem efeito na execução.
