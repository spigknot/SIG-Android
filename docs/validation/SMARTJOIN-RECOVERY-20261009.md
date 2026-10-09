# SmartJoin Android: memória, retomada e avisos — 2026-10-09

## Falha observada

O aplicativo fechou em `Validando emenda 1` após montar os dois vídeos longos.
O crash registra `OutOfMemoryError` no callback de logs do FFmpegKit, seguido
de abort JNI. O heap Java atingiu o limite de 512 MiB. As sondagens de pacotes
imprimiam grandes JSONs através da ponte de logs nativa; o histórico mantinha
esses objetos, e o parser construía outra representação completa em memória.

Os dois corpos TS, a emenda e o MP4 final de 10.235.190.787 bytes permaneceram
no cache. A falha não era uma rejeição dos quadros da emenda.

## Correção e comportamento

- FFprobe escreve a sondagem em arquivo com `-o`. Pacotes usam saída compacta
  e leitura incremental, mantendo somente os dados necessários ao plano.
  Metadados pequenos continuam em JSON, também escritos em arquivo.
- Após montar o arquivo, um checkpoint registra duração, contagem de quadros,
  FPS, limites de lacuna, áudio, posições das junções e tamanho do MP4.
  Falhas de validação não eliminam o material pronto.
- Reabrir a ferramenta oferece `Retomar` para uma saída pendente. A retomada
  valida o arquivo existente sem repetir cópia, encode ou montagem.
  Tentativas anteriores sem checkpoint podem reconstruí-lo a partir dos
  segmentos e do manifesto preservados. Tamanho alterado, peças ausentes ou
  manifesto inválido impedem essa recuperação.
- Uma montagem concluída pode ser visualizada, salva e compartilhada mesmo
  quando a validação encontra um problema. Um aviso persistente junto às ações
  de saída informa a falha específica. Cancelamento e saída incompleta não
  são tratados como resultados com aviso.
- As conferências de contagem, PTS, DTS, duração, áudio e decode nas junções
  continuam ativas. A estratégia de copiar os corpos permanece intacta.

## Evidência

- 50 testes focais: 24 de planejamento e 26 de timing/parser/recuperação,
  sem falhas, erros ou skips.
- `:app:assembleDebug`, `:app:lintDebug`, `git diff --check` e
  `scripts/check-module-map.ps1 -Quiet`: passaram.
- O FFmpegKit n6.0 do próprio celular validou o MP4 completo preservado:
  100.995 quadros e as duas janelas de decode corretas. Não foi necessário
  montar novamente o arquivo nem recodificar qualquer trecho.
- O harness nativo tem heap máximo de 256 MiB, inferior ao limite de 512 MiB
  do app que sofreu o crash. Na prova final, o uso ao terminar foi
  190.431.376 bytes, com zero objetos de log retidos nas sessões de sondagem.
  Esse valor é uma amostra, não uma medição do pico total de memória nativa.
- A prova com contagem esperada artificialmente aumentada para 100.996
  devolveu aviso com os valores reais e esperados, sem abortar a montagem.
- As provas de cancelamento e tamanho de arquivo diferente continuaram
  rejeitadas. Nenhuma dessas provas moveu ou apagou o MP4 preservado.

O APK final foi instalado por ADB, preservando o cache. A prova de tela
confirmou o diálogo `Retomar SmartJoin`, com `Retomar` e `Agora não`.
O usuário pode retomar a saída completa já preservada.

APK instalado: `sig-smartjoin-20261009-retomada.apk`.
SHA-256: `69fe10ae28922e29627fcd68e7d70a2ff676db42b0668a6d57a5df206747e904`.
A matriz nativa usou o candidato de SHA-256
`5a4ca28e18465b2d1019a5b3ab99ae649e153bc5c085d2190aa8eeb688b4a366`;
a diferença final consiste nos logs de diagnóstico da busca de retomada.
Evidência detalhada e harness ficam em `build/smartjoin_vfr_repro/`, ignorado
pelo Git. Nenhuma publicação foi feita.
