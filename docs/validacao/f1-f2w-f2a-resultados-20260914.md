# F1 / F2-W / F2-A — correções de dano comprovado (14/09/2026)

Estado: **implementado, gates verdes, prova de campo pendente** (re-executar a bateria
T01–T04 nos builds novos, com emulador/aparelho e o harness do SIG Windows).

| Fase | O que mudou | Arquivos | Gate |
|---|---|---|---|
| F0 | manifesto de referência com SHA-256 completo do corpus, saídas de referência, ffmpeg, APK e HEADs | docs/validacao/manifesto-referencia-20260914.md | — |
| F1 | o remux leva a seleção até o muxer final (-map 0:v:0 -map 0:a? + -map_metadata 0) e **valida o inventário** antes de abandonar o intermediário; aviso ao operador quando descarta o resultado ou deixa streams fora do plano | FfmpegOutputRemuxer.kt, FfmpegCutActivity.kt | 425/0 + lintDebug + assembleDebug |
| F2-A | o corte preciso reencoda **cada faixa** de áudio em AAC com o perfil da própria faixa (T01/T02); inventário por faixa via MediaExtractor; resumo na lista de tarefas | FfmpegMediaPolicies.kt, FfmpegCutActivity.kt | idem |
| F2-W | perfil por faixa no Windows: AudioTrackProfile + _precise_audio_args no corte preciso e nas bordas do SmartCut (antes, a faixa B estéreo/48 kHz saía mono/44,1 kHz) | SIG Windows: src/ffmpeg_tools_panel.py, tests/test_audio_track_profiles.py | syntax + tests + ui-smoke + build_dev + preflight = OK |

Commits: Android `c06520b`; Windows `46422b1` (+ F0 no Android).

## Pendente (plano do agente de decisão)
- **Prova de campo**: re-executar T01/T02 (áudio do corte preciso), T03/T04 (inventário de faixas) e T06 (SmartCut) nos builds novos — exige emulador/aparelho e o harness do Windows.
- **F3** crop: coordenada ímpar (y=87 entrega 86) — alinhar à grade e mostrar o valor efetivo nos dois apps.
- **F4** nome/efeito da transição do Smart Insert (Windows) e caminho de cópia do Extrair.
- **F5** áudio contínuo no SmartCut (provar antes/depois com C1c antes de mudar o núcleo).
- **F6–F10** limites do Sem Reencode, SmartJoin (N4: 2/5/20 clipes), Extrair, Limpar, preset/capítulos/HDR/entrega.

## Prova de campo (14/09, após a correção dos especificadores)

Tudo medido nos apps reais, comparando com o manifesto:

| Prova | Entrada | Antes | Depois |
|---|---|---|---|
| T02 Android — áudio do corte preciso | C1b (bipes), [1,4→4,6], Reencode Completo, emulador | 3,62 s com 0,44 s de áudio anterior ao corte | **3,227 s**, 80 pacotes, bipes em 0,12/1,12/2,12/3,12 (alinhado) |
| T04 Android — inventário de faixas | C3b (A=mono/44,1k, B=estéreo/48k), corte preciso | perdia 1 faixa no remux; faixa B saía mono/44,1k | **2 faixas**: 44100/1 e 48000/2 |
| T04 Windows — perfil por faixa | C3b, corte preciso (probe + pipeline reais) | faixa B saía mono/44,1k | **2 faixas**: 44100/1 e 48000/2 |

**Achado que só a prova de campo pegaria:** `-ar:0`/`-ac:0` (especificador NU) casa por
índice GLOBAL de stream — e o stream 0 é o vídeo. A forma correta é qualificada:
`-ar:a:0`/`-ac:a:0`. Medido com o ffmpeg de referência:

| Forma | Faixa A (fonte mono/44,1k) | Faixa B (fonte estéreo/48k) |
|---|---|---|
| `-ar:0 -ac:0 -ar:1 -ac:1` | 48000/2 (errado) | 48000/2 |
| `-ar:a:0 -ac:a:0 -ar:a:1 -ac:a:1` | **44100/1** | **48000/2** |

Os testes unitários codificavam a forma errada e passavam; corrigidos junto (os dois apps).

## F3 — contrato espacial do recorte (14/09)

O retângulo agora sai alinhado à grade par (origem E tamanho) e o rótulo mostra o valor
efetivo — o mesmo que o filtro aplica. Prova nos apps reais:

| Prova | Antes | Depois |
|---|---|---|
| Gesto conhecido no app Android (C4c) | `crop=322:162:100:87` (y ímpar) | **`crop=322:162:100:86`** |
| Rótulo na lista de tarefas | não existia valor efetivo | **"Recorte por seleção: 322 x 162 pixels a partir de (100, 86)"** |
| Arquivo gerado | — | **322 x 162** |
| Geometria (ffmpeg de referência, C4e) | pedir y=87 entrega a fonte linha 86 | pedir y=86 entrega a fonte linha 86 (promessa == entrega) |

Implementação: `cropPixels` (Android) e `selection_crop_pixels` (Windows) alinham x0/y0 para
baixo e mantêm largura/altura pares; `cropLabel`/`selection_crop_label` renderizam os mesmos
números para o rótulo e o diálogo de confirmação. Cobre Cortar e Girar nos dois apps.

## F4a — rótulo da transição do Inserir (Windows)

O mesmo par (rótulo, curva) tinha EFEITO diferente por modo: no Smart Insert cada curva
suaviza apenas o trecho **inserido** (afade); no Reencode Completo as curvas viram
**crossfade** nas emendas. O mesmo "Linear" prometia, portanto, coisas diferentes.

Agora o rótulo diz o efeito:
- Smart Insert: `Linear (fade só no trecho inserido)`, …
- Reencode Completo: `Crossfade linear`, … (o `Fade in/out` continua igual nos dois)

Prova: uma preferência salva ("Linear") continua valendo — os três rótulos resolvem para a
mesma curva (`tri`) e geram o **mesmo plano**; o plano executado de verdade nos arquivos do
T08 (principal 10 s + inserido 2 s, 0,2 s de transição, inserção em 3 s) dá **12,000 s**,
contra os 12,03 s de referência do T08 (fade só no inserido).

**Deliberadamente não mudado:** os rótulos do Inserir no Android ("Curva linear",
"Seno de quarto de onda"…) são neutros e o efeito lá é crossfade nos dois lados — não
prometem efeito diferente do que entregam, então ficam como estão.

## F4b — Extrair áudio copiando quando o pedido é o próprio stream (Windows)

O Windows **sempre** reencodava ao extrair; o Android já copiava quando o pedido coincide com
o stream de origem. Agora os dois usam o mesmo critério (`extract_can_copy`): mesma família de
codec/extensão **e** mesma taxa **e** mesmos canais **e** sem recorte.

Prova com os pacotes de áudio (md5 do bitstream):

| Arquivo | md5 dos pacotes |
|---|---|
| Fonte (C1.mp4, aac 44,1k mono) | `7bde28e614f058570bd0ec3c02f24a21` |
| Extraído com m4a/44100/1 (**cópia**) | `7bde28e614f058570bd0ec3c02f24a21` — **idêntico** |
| Extraído com m4a/48000/1 (reencode) | `9436c3e74fd9f307dc053b65d0fc6d54` — diferente (é reencode) |

O passo também se anuncia: "Copiando áudio sem reencodar" contra "Extraindo <arquivo>" — o
operador vê qual caminho foi usado, como no Android.

## N4 — Juntar com 2/5/20 clipes (14/09)

Clipes gerados: 20 × 1,52 s (o alvo era 1,5 s; a 25 fps o encoder fecha em 38 quadros, e isso
virou parte da medida), cada um com um nível de cinza próprio e um bipe de 100 ms em 0,5 s.

**Resposta principal — o Juntar NÃO acumula deriva.** Nos três tamanhos os bipes ficam
espaçados exatamente pela duração do clipe (1,520 s), sem nenhuma deriva por emenda:

| Caso | duração | quadros | bipes (esperado: 0,521 + 1,520·i) |
|---|---|---|---|
| Cópia K=5 | 7,621 s | 190 | 0,521 / 2,041 / 3,561 / 5,081 / 6,601 |
| SmartJoin K=2 | 3,061 s | 76 | 0,521 / 2,041 |
| SmartJoin K=5 | 7,621 s | 190 | 0,521 / 2,041 / 3,561 / 5,081 / 6,601 |
| SmartJoin K=20 | 30,421 s | 760 | os 20 bipes, o último em 29,401 |

(Os "+20 ms por clipe" da primeira medição eram **meu gerador**: 1,5 s a 25 fps = 38 quadros =
1,52 s. O Juntar reproduz a duração real de cada clipe.)

**Achado novo — com transição, o SmartJoin entrega o conteúdo FORA DE ORDEM.** Rodando o
pipeline real com "Fade in/out" 0,5 s em 5 clipes, a linha do tempo medida no arquivo final foi
clipe **2 → 3 → 4 → 5 → 1** e depois as emendas, em vez de 1 → 2 → 3 → 4 → 5 com as emendas
intercaladas. Evidência: série crua de níveis de cinza amostrada a cada 0,2 s
(`24 24 24 36 36 36 48 48 61… 12 12 12 12 12 …`) e os bipes de áudio em 0,0–0,2 s / 1,2–1,3 s
(em vez de 0,5 + 1,52·i). O log do app anunciava 7,60 s esperados e entregou 7,822 s.

Causa provável (apontada no código): em `_smart_join_execute` os segmentos são acumulados em
**dois laços separados** — primeiro todos os corpos, depois todas as emendas — e o índice usado
para ler `paths[index]` é a posição do laço, não `clip_plan.index`. O plano em si está na ordem
certa (`ClipPlan(index, …)` por `enumerate(sources)`) e o total esperado bate com o log
(`fade_in_out=True` → soma dos clipes), então a divergência nasce na montagem dos `pieces`.

**Pró passo (F7):** ordenar `pieces` (corpo 1, emenda 1, corpo 2, emenda 2, …) usando
`clip_plan.index`/`junction.index`, **verificar o mesmo trecho no Android** (o pipeline foi
portado de lá — é provável que compartilhe o defeito) e provar com este mesmo roteiro N4
(a ordem `1>2>3>4>5` com as emendas intercaladas e os bipes em 0,5 + 1,52·i).

## F7 — ordem dos segmentos do SmartJoin (14/09)

**O defeito era do port para o Windows.** O Android sempre montou intercalado — corpo j e
emenda j no MESMO laço, `plan.clips.forEachIndexed { … plan.junctions.getOrNull(index)?.let { … } }`.
O port separou em dois laços e as emendas caíam todas no fim: medido no N4, a linha do tempo
saía 2>3>4>5>1 (e os bipes em 0,0/1,2 s em vez de 0,5 + 1,52·i).

**Correção (Windows, aproximando do Android):** os segmentos são montados por
`smart_join_segment_order(clip_count, has_body, junction_indexes)` — corpo j, emenda j,
corpo j+1, … — e o concat usa essa lista.

**Prova (mesmo roteiro N4, 5 clipes, "Fade in/out" 0,5 s):**

| Medida | Antes | Depois |
|---|---|---|
| Ordem da linha do tempo | 2 > 3 > 4 > 5 > 1 | **1 > emenda > 2 > emenda > 3 > emenda > 4 > emenda > 5** |
| Bipes | 0,0–0,2 s / 1,2–1,3 s | **0,521 / 2,075 / 3,641 / 5,207 / 6,773** |

**Observação que fica para o acompanhamento:** no modo com fade o total sai ~50 ms por emenda
acima do que o próprio app espera (7,822 s contra 7,60 s em 4 emendas; sem transição o desvio é
+20 ms no arquivo inteiro). Não é o defeito de ordem — é o custo do fade nas emendas — e entra na
mesma discussão do áudio contínuo (F5).

## F7 — verificação do lado Android (no aparelho, com o mesmo roteiro N4)

Fiz o mesmo teste no app Android (emulador): 5 clipes marcados, "Fade in/out" 0,5 s, SmartJoin
ligado. O app anunciou **7,600 s** processados e entregou **7,797 s** de vídeo / 7,842 s de áudio
(190 quadros de vídeo) — dentro do mesmo perfil do Windows depois da correção.

A linha do tempo medida no arquivo final seguiu **exatamente a ordem de entrada** — o seletor de
arquivos entregou `clip_00 + clip_02 + clip_03 + clip_01 + clip_04` e o arquivo saiu
`1 → fade → 3 → fade → 4 → fade → 2 → fade → 5`, com as emendas **intercaladas**:

| t | nível | leitura |
|---|---|---|
| 0,0–1,0 | 12 | clipe 1 |
| 1,2–2,0 | 7, 2, 6, 20, 35 | fade (emenda) |
| 2,2–2,6 | 36 | clipe 3 |
| 2,8–3,4 | 22, 7, 12, 30 | fade (emenda) |
| 3,6–4,2 | 48 | clipe 4 |
| 4,4–5,0 | 29, 9, 6, 15 | fade (emenda) |
| 5,2–5,6 | 24 | clipe 2 |
| 5,8–6,6 | 22, 13, 3, 20, 43 | fade (emenda) |
| 6,8–7,6 | 61 (=60) | clipe 5 |

Observação de método: os valores das emendas são soma ponderada dos dois clipes (ex.: 12 = 0,25 × 48,
o fade-in do clipe 4) — não confundir um valor de fade com a reaparência de outro clipe, que foi o
que a primeira leitura sugeriu.

Conclusão: **o Android cumpre o contrato** (ordem + emendas intercaladas); a correção do F7 levou o
Windows ao mesmo comportamento.
