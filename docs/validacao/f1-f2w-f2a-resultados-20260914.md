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
