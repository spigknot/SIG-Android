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
