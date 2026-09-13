# Rodada A de testes — resultados (T01, T02, T03, T04, T05, T06, T08)

Data: 13/09/2026. Executado pelo desenvolvimento do SIG, nos dois aplicativos
(SIG Android em emulador Pixel_9 + SIG Windows no PC), **sem alterar nenhuma
linha de código** de qualquer um dos projetos.

**Método.** Corpus controlado: C1 = H.264/AAC 6 s, 640x360, 25 fps, GOP 1 s;
C1b = igual, com **bipes de 1 kHz** em 0,5/1,5/2,5/3,5/4,5/5,5 s; C2 = C1 sem
áudio; C3 = C1 com **duas faixas AAC** (440 Hz e 880 Hz, 48 kHz mono);
C3b = duas faixas de perfis **diferentes** (A: mono/44,1k, B: estéreo/48k);
C4c = vídeo h264 **sem perdas** com identidade em cada **linha** (luma =
`(linha%8)*32`), para medir a coordenada do crop; C5 = principal PCM 10 s +
inserido PCM 2 s. Operação comparada: corte **[1,4 s → 4,6 s]** (pedido de
3,20 s = **80 quadros**) salvo pelo próprio app em cada plataforma; análise com
ffprobe/ffmpeg (por stream: PTS, contagem de quadros, pacotes de áudio, bipes,
inventário de faixas, pixels das bordas).

---

## T01 — Reproduzir o excesso e localizar a sua origem

| Saída | Duração | Quadros de vídeo | Vídeo começa em | Áudio (bipes) | Veredicto |
|---|---:|---:|---:|---|---|
| Windows — Reencode Completo | 3,20 s | 79–80 | fonte 1,4 s | — | exato |
| Windows — Sem Reencode | 3,22 s | 80 | — | — | aproximação do modo |
| Windows — SmartCut | 3,29 s | **80 exatos** | — | — | +0,09 s = lead de 2 quadros do 1º trecho TS |
| Android — Reencode Completo | **3,62 s** | 79 | fonte **1,4 s** ✓ | **0,5 / 1,5 / 2,5 / 3,5** | ver abaixo |
| Android — Sem Reencode | 3,62 s | 90 | fonte ~1,0 s | — | deslize ao keyframe (contrato do modo) |
| Android — SmartCut | 3,30 s | **80 exatos** | — | — | +0,10 s = lead |

**Origem do excesso do Android (medido, não inferido):**

1. O **vídeo está correto**: o primeiro quadro da saída corresponde à fonte em
   **1,4 s** (diferença média de pixels 0,08 contra a fonte em 1,4 s e 3,61
   contra a fonte em 1,0 s) e há ~80 quadros.
2. O **áudio não está**: os bipes saem em **0,5 / 1,5 / 2,5 / 3,5 s**, ou seja o
   áudio da saída começa na fonte em **1,0 s** — **0,44 s de áudio anterior ao
   início pedido**.
3. Consequência no contêiner: duração 3,62 s, primeiro PTS de vídeo **0,442 s**
   e áudio a partir de 0 — o arquivo ganha um **prelúdio de ~0,44 s só com
   áudio** (imagem parada) antes do primeiro quadro.
4. **Não é o `-avoid_negative_ts make_zero`**: o mesmo comando sem `make_zero`
   dá a mesma duração (3,62 s).

**Isto é um defeito contra a promessa do modo ("limite exato quadro a quadro"):
conteúdo de áudio fora do intervalo pedido + imagem parada no início.**

## T02 — O AAC isola e resolve?

Variante do **comando real do app Android**, mudando **somente** o tratamento do
áudio (mesmo vídeo, preset, seek, contêiner):

| Variante | Duração | Bipes na saída | Leitura |
|---|---:|---|---|
| Áudio **copiado** (como o app faz) | 3,62 s | 0,5 / 1,5 / 2,5 / 3,5 | áudio começa na fonte em 1,0 s ✗ |
| Áudio **AAC** (como o Windows faz) | 3,23 s | 0,12 / 1,12 / 2,12 | áudio começa em 1,4 s ✓ (0,02 s = atraso normal do encoder) |
| `-ss` na **saída** (alternativa) | 3,22 s | — | também corrige |

O vídeo permanece com **80 quadros** na variante AAC → **nada foi cortado
indevidamente**. **Hipótese confirmada: a cópia do áudio é a causa do excesso e
a política de áudio (D1) é a correção.**

## T03 — O remux final perde faixas?

Fonte **C3**: 1 vídeo + **2 faixas de áudio**.

| App | Faixas na saída entregue | Veredicto |
|---|---|---|
| Windows | 3 (vídeo + 2 áudios) | aprovado |
| **Android** | **2 (vídeo + 1 áudio)** — perdeu uma faixa | **reprovado** |

Mecanismo reproduzido isoladamente: o intermediário do Android sai com as 3
faixas e o **remux final (sem `-map`) faz seleção automática** → 2 faixas; com
`-map 0` → 3 faixas. É perda silenciosa, no último passo do pipeline.

## T04 — Parâmetros de uma faixa aplicados às outras?

Fonte **C3b** (A: mono/44,1 kHz | B: estéreo/48 kHz), corte preciso:

| App | Faixa A (saída) | Faixa B (saída) | Veredicto |
|---|---|---|---|
| **Windows** | mono / 44,1 kHz | **mono / 44,1 kHz** (era estéreo/48k) | **reprovado** — perfil da 1ª faixa aplicado a todas |
| Android | áudio copiado (mantém perfis), mas perde a 2ª faixa no remux (T03) | — | reprovado por outro motivo |

Confirmado no código do Windows: o probe lê a **primeira** linha `Audio:` e o
comando aplica `-ar/-ac/-b:a` **globais** a todas as faixas.

## T05 — O crop `322:162:100:87` começa na linha 87?

Teste com C4c (identidade por linha) e o filtro **exato que os apps usam**
(`crop=322:162:100:87`, `exact=0`):

| Filtro | Primeira linha da saída | Corresponde a | Veredicto |
|---|---|---|---|
| `crop=322:162:100:87` (`exact=0`, o dos apps) | luma 192 | **linha 86** da fonte | **reprovado** (o app anuncia 87) |
| `crop=322:162:100:87:exact=1` | luma 224 | linha 87 da fonte | aprovado |

Medido também **no artefato gerado pelo app Android** com seleção desenhada por
toque: a confirmação exibiu *(100, 87)* e a saída começou na **linha 86** — mesma
divergência do teste isolado. O `x=100` e as dimensões pares não sofrem
arredondamento; o **y ímpar** sofre (alinhamento à croma do 4:2:0).

## T06 — O miolo do SmartCut está integralmente preservado?

Corte [1,4 → 4,6]: plano = cabeça [1,4–2,0] recodificada (15 q) + **miolo
[2,0–4,0] copiado (50 q)** + cauda [4,0–4,6] recodificada (15 q) = **80 q**.

| App | Quadros | Regiões medidas | Miolo copiado | Veredicto |
|---|---:|---|---|---|
| Windows | **80** | 15 recod. + **50 copiados** + 15 recod. | 50/50 quadros **bit a bit iguais** à fonte, `origem = k+35` **constante** | **aprovado** |
| Android | **80** | 15 recod. + **50 copiados** + 15 recod. | 50/50 iguais, `origem = k+35` constante | **aprovado** |

Nenhum quadro repetido ou omitido nas emendas. O arquivo sai ~0,09–0,10 s mais
longo que o pedido por causa do **lead de ~2 quadros do primeiro trecho MPEG-TS**
(comportamento igual nos dois apps; vale documentar na interface).

## T08 — "Linear" significa o mesmo no Smart Insert e no integral?

Fonte C5 (principal 10 s + inserido 2 s, inserção em 5 s, transição **Linear
0,2 s**; "Linear" = curva `tri` nos dois apps):

| Modo | Duração medida | Efeito aplicado | Veredicto |
|---|---:|---|---|
| Windows **Integral** | **11,60 s** | `acrossfade` entre vizinhos (sobreposição real) | como esperado |
| Windows **Smart Insert** | **12,03 s** | `afade` **somente no áudio inserido**, sem sobreposição | **divergência de semântica com o mesmo rótulo** |
| Android | não tem Smart Insert; "Linear" entra no caminho de crossfade (igual ao integral) | — | — |

Ou seja: **no mesmo app, com o mesmo rótulo "Linear", um modo sobrepõe e o outro
apenas escurece o inserido** — e a duração difere em 0,43 s. É defeito de
semântica de interface, confirmado nos builders e nos artefatos.

---

## Quadro-resumo

| Teste | Windows | Android | Natureza |
|---|---|---|---|
| T01 (duração do corte preciso) | aprovado | **reprovado** (3,62 s com 0,44 s de áudio anterior ao corte) | defeito real, causa medida |
| T02 (AAC resolve) | — | **confirmado** (3,23 s, áudio alinhado, 80 quadros) | correção pequena e validada |
| T03 (faixas no remux) | aprovado (3 faixas) | **reprovado** (entrega 2 de 3) | perda silenciosa no remux final |
| T04 (perfil por faixa) | **reprovado** (1ª faixa imposta às demais) | áudio copiado (perde faixa antes) | defeito nas duas plataformas |
| T05 (crop y ímpar) | reprovado (anuncia 87, entrega 86) | reprovado (idem, no artefato do app) | defeito compartilhado do filtro `crop` padrão |
| T06 (miolo do SmartCut) | **aprovado** (50/50 bit-exatos, plano exato) | **aprovado** (idem) | sustentação para manter o modo |
| T08 (semântica de transição) | **reprovado** (mesmo rótulo, efeitos diferentes) | não se aplica | correção de nome/ajuda ou de implementação |

**Nada neste relatório foi implementado** — os artefatos e comandos ficam
guardados para repetir a bateria depois de cada correção (mesmas entradas, mesma
medição, antes e depois).

---

## Anexo — complementos pedidos na revisão 2 (R1, R2, R5, R6, R8)

### R1 — os quatro bipes (a tabela anterior estava incompleta)

Perfil de energia a cada 100 ms, no áudio decodificado:

| Saída | Duração do áudio | Marcadores presentes |
|---|---:|---|
| Variante AAC (comando real do app, só o áudio trocado) | 3,228 s | **0,10 / 1,10 / 2,10 / 3,10 s** — os **quatro** |
| Arquivo gerado **pelo app** (áudio copiado) | 3,622 s | **0,50 / 1,50 / 2,50 / 3,50 s** — os quatro, deslocados **+0,40 s** |

O quarto bipe existe e está na posição esperada (3,10 s) na variante AAC; a
tabela anterior listava três por limitação do meu detector (o último bipe cai nos
últimos 130 ms do arquivo e não fechava a janela de detecção). **Não há perda de
cauda em nenhum dos dois**; o deslocamento do arquivo do app é uniforme
(+0,40 s em todos os marcadores), o que reforça o diagnóstico do prelúdio.

### R2 — contagem de quadros: 80 em todos

Com decodificação 1:1 (`-fps_mode passthrough`, sem duplicação/descarte):

| Arquivo | Quadros |
|---|---:|
| Windows preciso | **80** |
| Android preciso | **80** |
| Variante AAC | **80** |
| Windows SmartCut | **80** |
| Android SmartCut | **80** |

Esperado: índices 35–114 da fonte (1,40 s a 4,56 s) = 80 quadros.
O intervalo “79–80” do relatório anterior foi **erro de medição meu** (parsing do
CSV de PTS naquela passada); a contagem correta é 80 em todos os casos, com o
primeiro quadro confirmado na fonte em 1,40 s.

### R5 — crop sem ambiguidade (dois padrões, período 8 e período 9)

O padrão de período 8 se repete a cada 8 linhas; para eliminar essa ambiguidade,
o teste foi repetido combinando **dois padrões** (período 8 e período 9 — o que
deixa 5 candidatos em 360 linhas: 14 / 86 / 158 / 230 / 302):

- `crop=322:162:100:87` → primeira linha da saída = **fonte linha 86** (o
  candidato mais próximo de 87 é 86). **Conclusão mantida, agora sem ambiguidade.**
- Eixo **x** (mesmo mecanismo): `crop=322:162:101:87` (x ímpar) entrega a
  primeira coluna da **fonte 100** — o arredondamento ocorre também no x.

### R6 — os 30 ms do Smart Insert (medidos nas partes reais do app)

| Parte (gerada pelo próprio app) | Duração | Amostras (48 kHz) |
|---|---:|---:|
| Peça esquerda `[0; 5)` com `-c copy` | **5,030 s** | 241.440 (**+1.440 = +30 ms**) |
| Áudio inserido 2 s (com `afade curve=tri` 0,2 s) | 2,000 s | 96.000 (exato) |
| Peça direita `[5 s; fim)` com `-c copy` | 5,000 s | 240.000 (exato) |
| Concat final | 12,030 s | 577.440 = soma exata das partes |

Decomposição: **400 ms = as duas sobreposições de 0,2 s que o Smart Insert não
faz** (diferença editorial); **30 ms = overshoot de pacote da peça esquerda em
`-c copy`** (a mesma família de aproximação do modo de cópia, não é atraso de
AAC — a fonte e as partes são PCM).

### R8 — rastreabilidade dos binários

| Item | Valor |
|---|---|
| ffmpeg do SIG Windows | `8.0.1-full_build` (gyan.dev) |
| ffmpeg embutido no Android | ffmpeg-kit 6.1.1 (pacote nativo, R2) |
| APK usado na bateria Android | `app-debug.apk`, 9.049.127 bytes, sha256 `02ab6652c780beedabb0eb7e44b4808b…` |
| HEAD do SIG Android na execução | `6714260` |
| HEAD do SIG Windows na execução | `b549e60` (com 3 alterações locais do próprio usuário no painel/testes) |
| Entradas (sha256, prefixo) | C1 `ae6af91b…`, C3 `46af02df…`, C4c `e64507fe…`, C1b `ac9d0453…` |

### O que permanece sem prova

- R3/R5/R7 na versão **corrigida** (dependem da implementação autorizada).
- **R6 parcial**: a duração da saída do *integral* no **SIG Android** (mesmo caso
  10 s + 2 s) não foi medida — exige dirigir a ferramenta Inserir no app.
- T07 (posição relativa dos marcadores de áudio nas emendas do SmartCut) e o
  lead do SmartCut medido em **aparelho físico** (o emulador não certifica
  MediaCodec).

