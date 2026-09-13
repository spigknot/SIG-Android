# Relatório técnico — Divergências entre o SIG Windows e o SIG Android nas ferramentas de mídia (FFmpeg)

**Para:** decisão técnica de padronização
**De:** desenvolvimento do SIG (uso interno — PCSP)
**Data:** 13/09/2026
**Anexo de evidências:** `docs/bateria-comparativa-android-windows-20260913.md`

---

## 1. Contexto: o que são as ferramentas e por que precisam se comportar igual

O SIG é o app de apoio à investigação usado no dia a dia (transcrição de áudio e
vídeo, ocorrências, e um conjunto de ferramentas de mídia). Ele existe em duas
plataformas, com o **mesmo conjunto de ferramentas de mídia**:

- **SIG Windows** — desktop (Python/Tkinter), roda com um FFmpeg completo
  (codifica por GPU NVIDIA/Intel/AMD e por CPU).
- **SIG Android** — celular (Kotlin), roda com o FFmpeg embutido no app
  (pacote GPL) + os **encoders de hardware do próprio aparelho** (MediaCodec).

As ferramentas de mídia têm sempre o mesmo objetivo: **tratar arquivos de áudio e
vídeo de evidência sem perder qualidade e sem introduzir artefato** — cortar um
trecho com precisão, extrair o áudio, girar, juntar, limpar ruído, inserir um
segundo áudio e transcrever. O operador pode receber o mesmo material em qualquer
um dos dois e o resultado deve ser **equivalente e previsível**: mesmos limites de
corte, mesmo codec preservado, mesmo comportamento quando algo não é possível.

Hoje as duas implementações são próximas, mas **não idênticas** em alguns
parâmetros. Este relatório lista exatamente onde diferem, por que diferem, o
impacto prático e as opções de padronização — para que a decisão seja tomada com
o contexto completo.

### 1.1 As ferramentas (o que cada uma faz)

| Ferramenta | Objetivo |
|---|---|
| **Cortar** | Recortar um intervalo (início/fim) do vídeo ou áudio. Tem três modos: **SmartCut** (copia o trecho central sem reencodar e reencoda só as bordas até o tempo exato), **Reencode Completo** (reencoda tudo, limite exato quadro a quadro) e **Sem Reencode** (copia os streams, limite aproximado ao keyframe). Aceita **seleção de área** (recorte de pixels do quadro). |
| **Extrair áudio** | Retirar a trilha de áudio de um ou mais arquivos (wav/mp3/m4a/ogg/opus/flac). |
| **Girar vídeo** | Girar 90/180/270°, espelhar, recortar intervalo e (Windows) recortar área. Pode girar só nos metadados (sem reencodar) ou nos pixels. |
| **Juntar vídeos** | Juntar clipes do mesmo tipo; o "SmartJoin" tenta colar sem reencodar quando os clipes são compatíveis. |
| **Limpar áudio** | Reduzir ruído e normalizar, gerando um áudio adequado para transcrição. |
| **Inserir áudio** | Colocar um segundo áudio dentro de um vídeo, no ponto escolhido, com transição. |
| **Player/preview** | Reproduzir o material dentro da ferramenta, com zoom, arrasto do quadro e seleção de área (usada para recortar). |
| **Seletor de encoder** | Escolher ONDE processar: **GPU** (hardware) ou **CPU** (software), com um menu "Avançado" que lista os encoders reais da máquina que passaram numa sondagem. |

O critério de qualidade dessas ferramentas, do ponto de vista de evidência, é:
**preservar o que não precisa ser alterado** (copiar em vez de reencodar),
**ter limites previsíveis** (o que foi pedido é o que sai) e **nunca degradar em
silêncio** (se algo tiver de mudar — codec, resolução, encoder — o app avisa).

---

## 2. Como a comparação foi feita (método)

- Mesmos arquivos nos dois lados: vídeo H.264 e H.265 (HEVC), 640x360, 25 fps,
  6,0 s, GOP de 1 s, áudio AAC 96 kb/s mono.
- Mesmos parâmetros: mesmo intervalo (1,4 s → 4,6 s), mesmo modo, mesma seleção
  de área (322x162 a partir de (100, 87)).
- **Nenhuma linha de código foi alterada em nenhum dos dois apps.** No Windows os
  comandos foram capturados executando o pipeline real do app (import do módulo
  com a instrumentação usada pelos testes do próprio projeto); no Android, pelo
  app rodando em emulador, com os comandos lidos do log e as saídas analisadas
  por `ffprobe`.
- A prova mais forte: no SmartCut o trecho central deve sair **idêntico à
  fonte**. Medido nos dois lados: **Windows 39/49 e Android 38/49 quadros
  decodificados idênticos**, com o **mesmo deslocamento de 2 quadros**; as ~10
  diferenças são as transições de borda incluídas na janela de comparação.

**Resultado geral: o núcleo está alinhado.** As diferenças listadas abaixo são de
parâmetros e de estratégia de contêiner, não de lógica de corte.

---

## 3. Divergências encontradas (detalhadas)

### D1 — Áudio no "Reencode Completo": Windows reencoda, Android copia ⚠️ *a mais relevante*

**Windows** (modo Reencode Completo, com a política padrão "Precisão máxima (AAC)"):

```
ffmpeg -y -ss 1.4 -i entrada.mp4 -t 3.2 -map 0:v:0? -map 0:a? -sn -dn -vf null \
  -c:v libx264 -preset medium -crf 20 \
  -c:a aac -b:a 96k -ar 44100 -ac 1 \
  -map_metadata 0 -map_chapters -1 -movflags +faststart saida.mp4
```

**Android** (mesmo modo e mesmo intervalo):

```
ffmpeg -y -noautorotate -ss 1.400 -i entrada.mp4 -t 3.200 \
  -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map 0:t? -map_metadata 0 -map_chapters 0 \
  -c copy -c:t copy -c:v libx264 -preset ultrafast -crf 20 -avoid_negative_ts make_zero saida.mkv
```

O vídeo é reencodado nos dois (`libx264`, CRF 20 com a qualidade "Alta"). A
diferença está no áudio: **o Windows reencoda em AAC** com os parâmetros da
origem; **o Android mantém `-c:a copy`** (o áudio é copiado bit a bit).

**Por que acontece:** o Android carrega os "argumentos de cópia mapeada"
(`-c copy`, para preservar tudo quando possível) e acrescenta apenas o override
do vídeo. A escolha de qualidade de áudio na tela do Android só é aplicada no
corte de **áudio puro**; no corte de vídeo ela não entra no comando.

**Impacto prático:**
- *A favor do Android (copiar):* zero perda de qualidade do áudio e áudio
  exatamente igual ao original.
- *A favor do Windows (reencodar):* o áudio da saída fica com o **mesmo limite do
  vídeo** (fecha no tempo pedido), é sempre AAC (compatível com qualquer player),
  e evita carregar codecs estranhos da origem (ex.: Opus dentro de MP4).
- Hoje, no Android, a tela mostra "Qualidade do áudio" no corte de vídeo e essa
  escolha **não tem efeito** — é uma inconsistência de UI, independente de qual
  política vencer.

**Opções:**
1. **Padronizar no Windows (reencodar AAC)**: implementar a política de áudio no
   corte de vídeo do Android (AAC com os parâmetros da origem quando o usuário
   escolhe "Precisão máxima"; cópia quando escolhe "Copiar áudio"). Combina com a
   política que já existe na tela do Android.
2. **Padronizar na cópia**: os dois copiariam o áudio no corte de vídeo — máximo
   de fidelidade, mas o limite do áudio passa a escorregar para o pacote seguinte
   (alguns milissegundos a mais) e arquivos com áudio incompatível com o
   contêiner de saída podem exigir tratamento.
3. **Manter a diferença e tornar isso explícito** na interface (a tela do Android
   refletiria "áudio copiado" em vez de oferecer uma qualidade que não se aplica).

> Recomendação técnica: **opção 1** (mesma política nos dois, com "Precisão
> máxima (AAC)" como padrão), porque é o comportamento que o Windows já usa com
> material de evidência e mantém o fechamento exato do trecho nos dois streams.
> Se a prioridade for fidelidade absoluta, a opção 2 é defensável — mas então o
> Windows deveria mudar também, não só o Android.

---

### D2 — Preset do encoder de CPU: `medium` (Windows) × `ultrafast` (Android)

| | Windows | Android |
|---|---|---|
| Encoder CPU | `libx264 -preset medium -crf 20` | `libx264 -preset ultrafast -crf 20` |

**Por que acontece:** são escolhas históricas de cada app (o Windows usa preset
`medium`; o Android adotou `ultrafast` para reduzir o tempo de processamento no
celular).

**Impacto prático:** com o mesmo CRF, o preset `medium` produz **arquivo menor
com a mesma qualidade perceptual**, gastando mais tempo; o `ultrafast` é mais
rápido e gera arquivo maior. Medições desta bateria (SmartCut de 3,2 s em
640x360): Windows 124.665 bytes a 200 kb/s; Android 154.640 bytes a 272 kb/s.
Em material longo a diferença de tamanho pode ser relevante; a diferença de
qualidade subjetiva é pequena, mas existe (o `ultrafast` desliga ferramentas de
compressão que ajudam em cenas com movimento).

**Opções:**
1. **Padronizar no `medium`** nos dois (mais qualidade/espaço, mais tempo no
   celular).
2. **Padronizar no `ultrafast`** nos dois (mais rápido, arquivo maior).
3. **Escalonar por qualidade**: ligar o preset ao nível escolhido na tela
   (ex.: "Máxima/Muito alta" → `medium`; "Alta" → `fast`; "Média/Econômica" →
   `veryfast/ultrafast`). Dá controle ao operador e mantém os dois alinhados.

> Recomendação técnica: **opção 3**, com o nível "Alta" (padrão) caindo em
> `fast` — mantém tempo aceitável no celular e tamanho próximo do Windows. Se a
> simplicidade vencer, **opção 1**.
> Observação: esse parâmetro só afeta o caminho **CPU**; quando o corte usa o
> encoder de hardware do aparelho (MediaCodec) a qualidade é controlada por
> bitrate, não por CRF — a paridade aí é a de *bitrate de referência*, que já
> está alinhada (o alvo é derivado do bitrate do arquivo original).

---

### D3 — O que é preservado no "Sem Reencode": streams e metadados

**Windows** (política padrão "Vídeo e áudio"): `-map 0:v:0 -map 0:a? -sn -dn
-c copy -movflags +faststart` → **descarta legendas, dados e timecode**; (existe
também a política "Todos os streams", que mapeia tudo com `-map 0 -c copy`).

**Android**: `-map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map 0:t? -map_metadata 0
-map_chapters 0 -c copy -c:t copy` → **preserva legendas, dados, timecode,
capítulos e metadados**.

**Impacto prático:** em material de evidência, perder legendas/anexos pode ser
indesejado (o arquivo "magro" é mais compatível, o completo é mais fiel). O
arquivo copiado com streams exóticos pode falhar ao ser remuxado para MP4 em
players antigos; o Android já tem tratamento para isso (container final pelo
original).

**Opções:**
1. **Padronizar preservando tudo** (Android): mais fiel para evidência.
2. **Padronizar descartando** (Windows, política padrão): mais compatível.
3. **Expor a mesma política nas duas telas** ("Vídeo e áudio" × "Todos os
   streams"): o Windows já tem esse seletor; o Android ganharia o equivalente,
   com o mesmo padrão nos dois.

> Recomendação técnica: **opção 3**, com o padrão "Vídeo e áudio" nos dois
> (compatibilidade) e a opção de preservar tudo disponível — hoje as duas telas
> já têm espaço para isso.

---

### D4 — Estratégia de contêiner no SmartCut (MP4 direto × MKV + remux)

**Windows**: grava o arquivo final já em MP4, com `-t 3.2` (fecha no tempo
pedido), `-video_track_timescale 90000` e `-movflags +faststart`.

**Android**: grava um intermediário **MKV** (por causa de particularidades do
encoder de hardware do Android) e depois **remuxa** (`-c copy`) para o contêiner
original, aplicando `-movflags +faststart` no remux. O `-t` não é aplicado no mux
intermediário.

**Impacto prático:** medido — **nenhum impacto relevante**: Windows 3,29 s ×
Android 3,30 s para um pedido de 3,20 s (o excedente de ~2 quadros é o mesmo nos
dois e vem do "lead" de cada trecho MPEG-TS). O custo do Android é **um passo
extra de ffmpeg** (cópia, sem reencode) — pequeno, mas existe.

**Opções:**
1. Android passar a muxar direto no contêiner final quando ele for MP4/MOV
   (elimina o passo extra e permite aplicar `-t`).
2. Manter como está (o resultado medido é equivalente) e, no máximo, aplicar
   `-t` no mux intermediário para fechar o arquivo.

> Recomendação técnica: **opção 2** agora (custo/benefício baixo) com a 1 como
> melhoria futura — os números mostram que o operador não percebe diferença.

---

### D5 — Encoders disponíveis por plataforma (limitação real, não escolha)

| | Windows | Android |
|---|---|---|
| Hardware | NVENC (NVIDIA), QSV (Intel), AMF (AMD) | MediaCodec do aparelho (ex.: `c2.qti.*`, `OMX.qcom.*`) |
| Software | libx264 (H.264), **libx265 (H.265)** | libx264 (H.264) — **não há libx265** |
| Seleção | GPU/CPU + "Avançado" com os encoders que passam na sondagem | idem (hardware/CPU + "Avançado" com os nomes reais) |
| Regra do trecho curto | < 3 s vai para a CPU | < 3 s vai para a CPU (mesma regra) |

**Consequência:** no Android, um arquivo **HEVC** só pode ser preservado em HEVC
se o **hardware do aparelho** souber codificar HEVC; se não souber, o app avisa e
recodifica em H.264 (com confirmação do usuário). No Windows isso nunca acontece
porque existe o `libx265` por software.

**Opções:**
1. **Aceitar** (comportamento atual, já avisado na tela): simples, mantém o APK
   enxuto e rápido; em celulares modernos o encoder HEVC de hardware está
   presente.
2. **Embutir `libx265`** no pacote nativo do Android: paridade total, mas
   aumenta o pacote, é lento em celular (software) e traz implicações de licença
   (GPL x patentes de HEVC) para o app.
3. **Bloquear** corte em HEVC no Android: previsível, porém reduz a capacidade.

> Recomendação técnica: **opção 1** (com o aviso já implementado). A opção 2 só
> se houver exigência formal de preservar HEVC independentemente do aparelho.

---

### D6 — Detalhes de comando (baixo impacto, mas convém alinhar)

| Item | Windows | Android | Impacto |
|---|---|---|---|
| Capítulos | `-map_chapters -1` (descarta) | `-map_chapters 0` (preserva) | Cosmético; alinhar com a decisão do D3 |
| Filtro vazio | sempre escreve `-vf null` | só escreve `-vf` quando há recorte | Cosmético |
| Rotação | não usa `-noautorotate` no caminho de reencode | usa `-noautorotate -display_rotation:v:0 <n>` | Alinhado em resultado (testado no aparelho: HEVC saiu com tag `hvc1` e orientação correta) |
| `-avoid_negative_ts make_zero` | presente no SmartCut; ausente no Reencode Completo | presente nos dois | Cosmético/robustez |
| Áudio do SmartCut | reencodado (AAC) nos trechos | reencodado (AAC) nos trechos | **já alinhado** |
| Borda do SmartCut | reencoda espelhando o miolo (fps, pix_fmt, bitrate) | idem | **já alinhado** |

> Recomendação: alinhar apenas o que a decisão do D3 pedir; o resto é ruído de
> implementação sem efeito no arquivo final.

---

## 4. O que já está igual (verificado nesta bateria)

- **SmartCut**: mesma segmentação (0,6 s reencodado + 2,0 s copiado + 0,6 s
  reencodado para o pedido 1,4→4,6), mesmo `-bsf:v h264_mp4toannexb`, mesmo
  `-mpegts_flags +resend_headers+initial_discontinuity`, mesmo `-muxdelay 0
  -muxpreload 0`, mesmo concat com `-bsf:a aac_adtstoasc` e
  `-max_interleave_delta 0`.
- **Regra do trecho curto**: a borda de 0,6 s foi para o **libx264** nos dois
  (e, no Windows, GPU e CPU geraram **arquivos idênticos**, 124.665 bytes —
  a mesma regra vale lá).
- **Miolo bit a bit**: 38/49 (Android) e 39/49 (Windows) quadros idênticos à
  fonte, com o mesmo deslocamento de 2 quadros.
- **Crop**: filtro idêntico (`crop=322:162:100:87`), saída 322x162 nos dois, e a
  confirmação ao Executar com o mesmo texto.
- **Seleção de encoder**: mesma árvore de decisão (GPU quando possível; CPU com
  o motivo registrado; falha de hardware repete na CPU só naquela tarefa), mesma
  sondagem real por encode de 1 quadro, mesmo "Avançado" listando o que existe
  de fato.

---

## 5. Proposta de padronização (resumo para decisão)

| # | Divergência | Padrão proposto | Esforço | Risco de mudar |
|---|---|---|---|---|
| D1 | Áudio no Reencode Completo | mesma política nas duas telas, padrão **"Precisão máxima (AAC)"** (reencoda) | médio (Android) | baixo |
| D2 | Preset do libx264 | preset por nível de qualidade (`fast` no "Alta") nos dois | baixo | baixo |
| D3 | Streams no Sem Reencode | expor a política nas duas, padrão "Vídeo e áudio" | baixo/médio | baixo |
| D4 | Contêiner do SmartCut | manter (avaliar `-t` no intermediário) | baixo | nenhum medido |
| D5 | libx265 no Android | manter (aceitar) com o aviso atual | nenhum | — |
| D6 | Detalhes de comando | alinhar só o que D3 exigir | baixo | nenhum |

**Princípio sugerido para a padronização** (a validar): onde houver escolha, o
critério é (a) preservar o que não precisa ser alterado, (b) fechar os limites no
tempo pedido, (c) nunca mudar codec/resolução/encoder sem avisar na tela — e (d)
limitações de plataforma (hardware do celular, ausência de libx265) são assumidas
e comunicadas, não escondidas.

## 6. Cobertura e pendências

Comparado nesta bateria: **Cortar** nos três modos e com seleção de área/crop,
incluindo HEVC. Pendente de comparação com o mesmo método: **Girar**
(rotação + crop), **Juntar** (SmartJoin/reencode) e **Extrair/Inserir/Limpar** —
a mesma bateria pode ser repetida neles quando a decisão dos itens acima for
tomada, para não padronizar duas vezes.
