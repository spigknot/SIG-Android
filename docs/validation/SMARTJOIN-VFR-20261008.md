# SmartJoin Android: VFR e quadros de edit-list

## Falha e correção

O log real registrou `Emenda sem quadros`, antes de iniciar o encoder.
O contador convertia os pacotes copiados para segundos pelo FPS nominal.
Um corpo VFR com 69.313 pacotes e duração real de 2.300,186644 s passava
a ocupar 2.336,393258 s; a emenda seguinte recebia uma contagem negativa.

`SmartJoinTiming.Frames` conserva a duração dos corpos copiados. Apenas
as peças recodificadas usam `quadros / fps`, com arredondamento acumulado.
Manifesto e posições de validação usam esse mesmo relógio. O validador
conserva as lacunas existentes nas fontes VFR, mantendo as verificações
de contagem, duração, PTS únicos e DTS crescentes.

A conferência por `framecrc` também usa timebase de 1/90000 e PTS absolutos.
A janela seleciona os timestamps dos pacotes e compara a lista decodificada
completa. Isso evita que o encoder raw arredonde um quadro para fora do corte
da própria conferência; nenhum quadro extra ou ausente é tolerado.

O preroll descartado de uma edit-list não impede o uso do keyframe visível
seguinte. A tela agora mostra a causa de uma etapa que falha.

## Precisão sem transição

Zero segundos não aplica efeitos. Fontes compatíveis sem descartes positivos
podem usar concatenação direta por cópia.

O remux pode tornar visível um quadro final oculto pela edit-list. Quando
o descarte está depois de todas as imagens visíveis na ordem de decodificação,
limitar o corpo à contagem visível preserva essas imagens por cópia.
Quando esse descarte é uma referência de uma imagem visível posterior,
removê-lo por BSF perde a referência. Nesse caso o plano recodifica somente
o GOP final necessário; o restante permanece em cópia. Uma emenda que já
abrange esse GOP não recebe uma cauda adicional.

## Provas

- 45 testes de `SmartJoinPlannerTest` e `SmartJoinTimingTest`: passaram.
- `:app:assembleDebug` e `:app:lintDebug`: passaram.
- `scripts/check-module-map.ps1 -Quiet` e `git diff --check`: passaram.
- FFmpegKit n6.0 no Ace 2 Pro: transições Dissolver de 0, 0,5, 1 e 3 s
  passaram em HEVC/MediaCodec e H.264/libx264 com entradas VFR.
  Ambos os corpos permaneceram em cópia; os casos positivos recodificaram
  somente as emendas. Zero não executou encoder nas entradas sem cauda oculta.
- No APK final, a fixture com descarte necessário como referência passou
  sem transição: dois corpos em cópia e somente os GOPs finais recodificados.
  As três janelas de conferência corresponderam exatamente aos PTS esperados.
- No mesmo APK final, HEVC/VFR e H.264/VFR passaram novamente com 0 e 3 s.
  PTS e DTS permaneceram estritos; zero sem cauda não executou encoder.
- A sondagem somente de leitura dos arquivos completos no OnePlus 15
  confirmou FPS, durações, preroll e descartes finais com o próprio n6.0.
- A fixture da cauda preserva o `mdat` dos GOPs originais. O controle local
  decodificou todas as 115 imagens visíveis com CRC idêntico à fonte,
  sem erro de referência HEVC.

Uma conversão inicial de fixture H.264 usava timebase de encoder inadequada
e produzia PTS duplicados. O validador a rejeitou corretamente. A matriz
aceita usa a fixture corrigida com timebase de 1/90000.

A prova dos dados completos com transição de 3 s preserva 100.845 quadros
por cópia e agenda 150 quadros na emenda. A duração solicitada é
3.369,075756 s; a agenda difere apenas 3,02 ms por quantização de quadros.

APK final de teste, sem publicação:
`06cdbcb50f0b839a47991a219594a271f8b2d0d49828571ac0ad67fa911bf686` (SHA-256).
Logs, harness e mídias temporárias ficam nas pastas ignoradas
`build/smartjoin_vfr_repro/` e `build/smartjoin_vfr_unit/`.
Os vídeos completos não foram processados de ponta a ponta no celular;
a prova de processamento usa trechos dos mesmos bitstreams e dados
completos de sondagem para verificar o planejamento de vídeos longos.
