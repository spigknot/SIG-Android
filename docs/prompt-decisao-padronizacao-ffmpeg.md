# Prompt para o agente de decisão — padronização das ferramentas de mídia SIG Windows × SIG Android

> **Como usar:** enviar o texto abaixo (da linha "Você é o agente responsável…" até o fim) para o agente de IA do gerente. As referências de arquivo apontam para o repositório `D:\Projetos\SIG` (Android) e `D:\Projetos\SIG Windows` (lado Windows), se o agente tiver acesso; se não tiver, os números e conclusões necessários estão todos neste prompt.

---

Você é o agente responsável por **tomar as decisões finais de padronização** entre dois aplicativos que compartilham as mesmas ferramentas de mídia: o **SIG Windows** (desktop, Python/Tkinter + ffmpeg 8.0.1 completo) e o **SIG Android** (Kotlin + ffmpeg-kit 6.1.1 embutido + MediaCodec). Nosso objetivo é que **a mesma escolha do operador produza conteúdo equivalente nos dois**.

## 1. O que é o SIG e para que servem as ferramentas

O SIG é o app de apoio à investigação (uso interno PCSP). As ferramentas de mídia são: **Cortar** (com seleção de área/crop e três modos: SmartCut — copia o miolo entre keyframes e reencoda só as bordas; Reencode Completo — reencoda todo o trecho com limite exato; Sem Reencode — copia os streams, limite aproximado), **Extrair áudio**, **Girar vídeo**, **Juntar vídeos** (com SmartJoin), **Limpar áudio** (redução de ruído), **Inserir áudio** (intercala um áudio dentro de outro, no ponto escolhido, com transição) e **Transcrever** (fora deste escopo). Critério de qualidade do material de evidência: preservar o que não precisa ser alterado, ter limites previsíveis e **nunca degradar em silêncio** (codec, resolução, canais, faixas, timeline).

## 2. Método de avaliação que já está em uso (mantenha-o)

Três níveis de prova — (a) regra/comando, (b) **artefato real** (quadros, amostras, PTS, faixas), (c) app e player. Referência antes/depois de cada correção. Medir **por stream**: contagem de quadros 1:1 (`-fps_mode passthrough`, sem duplicação), primeiro/último PTS, pacotes/amostras por faixa, inventário de faixas, marcadores de áudio conhecidos. Hashes têm três semânticas distintas (arquivo inteiro × pixels decodificados × payload comprimido normalizado) — não misture. **Reproduzir o comando de um app no outro ambiente é diagnóstico, não é a prova do app**: a aprovação exige o artefato gerado pelo próprio app.

Armadilhas já encontradas (não repita): contagem por saída `rawvideo` **duplica quadros**; parsing de CSV de PTS pode perder o último; detector de bipes pode perder o último marcador (o arquivo termina antes de a janela fechar); padrão de teste com período repetido (ex.: `linha % 8`) é ambíguo (use dois padrões, ex. 8 e 9, e resolva por CRT).

## 3. Corpus de teste controlado (já construído)

C1 = H.264/AAC 6 s, 640x360, 25 fps, GOP 1 s · C1b = igual, com bipes de 1 kHz em 0,5/1,5/2,5/3,5/4,5/5,5 s · C1c = igual, com marcadores de 40 ms **a cada 250 ms** · C2 = C1 sem áudio · C3 = C1 com **duas faixas AAC** (440 Hz/880 Hz, 48 kHz mono) · C3b = duas faixas de perfis **diferentes** (A mono/44,1k, B estéreo/48k) · C4c = vídeo h264 sem perdas com identidade por linha `(linha%8)*32`; C4e = idem com período 9; C4f = identidade por coluna · C5 = principal PCM 48k estéreo 10 s + inserido PCM 2 s. Operação de referência dos testes de corte: **[1,4 s → 4,6 s]** (= 3,20 s = **80 quadros** da fonte; 4 bipes; 13 marcadores de 250 ms).

## 4. Resultados já medidos (fatos, não hipóteses)

| Teste | Resultado |
|---|---|
| **T01** corte preciso | Windows: 3,20 s exatos, 80 quadros. **Android: 3,62 s**, 80 quadros com o vídeo começando corretamente na fonte em 1,40 s, mas **0,44 s de áudio anterior ao corte** (bipes em 0,50/1,50/2,50/3,50 em vez de 0,10/1,10/2,10/3,10) e vídeo deslocado no contêiner (primeiro PTS 0,442 s). O excesso **não é** conteúdo de vídeo; é o áudio copiado desde o keyframe anterior (fonte 1,0 s). |
| **T02** variante AAC | Mesmo comando do Android com **apenas o áudio trocado por AAC**: 3,23 s, bipes em 0,10/1,10/2,10/3,10, **80 quadros** (nada foi cortado indevidamente). Causa confirmada. |
| **T03** inventário | Windows (política padrão) preserva **3/3** streams de C3; **Android entrega 2/3** — o remux final não tem `-map` e a seleção é automática (com `-map 0`: 3/3). |
| **T04** perfil por faixa | **Windows reprovado**: com C3b, a faixa B (estéreo/48k) saiu **mono/44,1k** — o probe lê a **primeira** linha `Audio:` e o comando aplica `-ar/-ac/-b:a` globais. |
| **T05/R5** crop ímpar | **Os dois reprovados** (mesmo filtro): `crop=322:162:100:87` entrega a **linha 86** (anuncia 87); `x=101` (ímpar) entrega a **coluna 100**; com `exact=1` entrega 87. Medido com dois padrões (CRT) e no artefato do próprio app Android. |
| **T06** miolo do SmartCut | **Aprovado nos dois** (inclusive no aparelho físico): 80 quadros, 15 recodificados + **50 copiados bit a bit** + 15 recodificados; miolo = fonte[50..99] com `origem = k+35` constante. |
| **T07** áudio nas emendas | **Idêntico nos dois**: sem silêncio/repetição (grade de 250 ms intacta), mas **+20 ms na cabeça, +40 ms na 1ª emenda, +20 ms na 2ª = +80 ms (~2 quadros) no fim**. É característica do desenho (áudio reencodado por trecho), não divergência entre plataformas. |
| **T08** semântica de transição | **Windows reprovado internamente**: "Linear 0,2 s" = **11,60 s com crossfade** no modo integral e **12,03 s com fade apenas no inserido** no Smart Insert. O Android só tem o caminho integral, coerente com crossfade; sem transição = **12,00 s** (medido no aparelho). |
| Encoders | Android: MediaCodec do aparelho + libx264 (sem libx265). Windows: NVENC/QSV/AMF/libx264/libx265. A **regra** já é a mesma: GPU preferida; trecho < 3 s vai para a CPU quando existe equivalente de CPU; falha de hardware repete na CPU só naquela tarefa, sem mudar a preferência. |

Identificação das execuções: ffmpeg Windows 8.0.1-full; ffmpeg do Android = ffmpeg-kit 6.1.1; APK `app-debug.apk` sha256 `02ab6652c780beedabb0eb7e44b4808b…`; HEAD Android `6714260`; HEAD Windows `b549e60` (com alterações locais do usuário no painel).

## 5. Critério de direção (o que já está determinado)

1. Se um lado tem **defeito medido** e o outro não → o defeituoso se aproxima do outro.
2. Se **os dois** têm defeito medido → os dois se aproximam de uma **terceira definição** (contrato comum).
3. Se a diferença é **física de plataforma** (encoder, contêiner intermediário, desempenho) → **não convergir**; manter a *regra* igual e documentar.

Aplicando o critério, já está determinado:
- **Android → Windows**: (i) o corte preciso deve aplicar a **política de áudio** (AAC) em vez de copiar; (ii) a seleção de faixas deve **chegar ao remux final** e o inventário ser validado antes de dar a operação como concluída.
- **Windows → Android**: (i) a transição "Linear" do Smart Insert deve significar o mesmo que no modo integral (crossfade) **ou** ser renomeada para o que realmente faz (fade somente no áudio inserido) — decisão de produto; (ii) o Windows deve ganhar o caminho explícito de **extração por cópia** que o Android já tem quando é possível.
- **Os dois → contrato comum**: (i) **perfil de áudio por faixa** (o Windows impõe o da primeira; o Android perderia faixas — a correção do Android deve nascer lendo o inventário, não copiando o `-ar/-ac` global); (ii) **crop com coordenada ímpar** (alinhar a seleção à grade e mostrar as coordenadas efetivas, ou rota `exact=1` validada); (iii) **atraso de ~2 quadros no fim do SmartCut** (aceitar/documentar ou áudio contínuo em uma passagem nos dois); (iv) limites e comunicação do modo **Sem Reencode**; (v) **preset CPU** (Windows `medium` × Android `ultrafast`) — escolher um candidato comum medido; (vi) **capítulos/timecode, HDR/10-bit e validação de sucesso na entrega**; (vii) **Limpar áudio**: "Forte" usa `afftdn` no Windows e `anlmdn` no Android, e a ajuda promete normalização de volume que o comando não faz — unificar por audição e corrigir o texto; (viii) **Extrair**: perfis comuns (original por cópia / conversão / transcrição / compacto) e seleção de faixa.

## 6. O que você deve entregar

**(A) Documento de decisão.** Para cada item das três listas acima: a **direção** (quem muda), o **comportamento-alvo** especificado de forma precisa (parâmetros, textos de UI, defaults), o **critério de aceite** (a medida e a tolerância), o **risco** e o que fica **fora** de escopo. Seja explícito onde a decisão é de produto (ex.: nome da transição, política de "Áudio com corte exato" × "Preservar áudio comprimido") e onde é técnica.

**(B) Plano de ação super detalhado.** Fases pequenas, **uma mudança por vez**, por aplicativo, com ordem e dependências; para cada mudança: o comportamento atual, o alvo, os arquivos prováveis (Android: `FfmpegMediaPolicies.kt`, `FfmpegCutActivity.kt`, `FfmpegOutputRemuxer.kt`, `FfmpegPreviewSelection.kt`, `FfmpegVideoQuality.kt`; Windows: `src/ffmpeg_tools_panel.py`, `src/video_encoders.py`), os **gates obrigatórios** (Android: `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug`; Windows: a suíte `pytest` + `scripts/ui_smoke.py` + rebuild do `dist/sig.exe`), as vacinas de teste que devem nascer junto, e o plano de reversão de cada fase. Inclua a ordem em que as duas plataformas devem ser mexidas para não padronizar duas vezes.

**(C) Se você julgar que ainda falta evidência para decidir algo: o plano de testes detalhado.** Para cada teste: objetivo, entrada (do corpus da seção 3 ou nova), procedimento exato (no app, não só o comando), o que medir, critério de **aprovado/reprovado/inconclusivo** definido **antes** da execução, e **qual decisão da seção 5 aquele teste desbloqueia**. Os testes já identificados como úteis e ainda não executados: identidade das pontas do "Sem Reencode" no Windows; SmartCut nos limites (início/fim exatamente em keyframe, trecho sem corpo copiável, vídeo sem áudio, HEVC); SmartJoin com transição e com sequência longa de clipes (verificar se o atraso de áudio cresce com o número de emendas); HEVC curto × hardware × fallback; Extrair áudio (cópia × conversão, faixa selecionada, lote com item sem áudio); Limpar áudio (audição comparada entre os dois algoritmos "Forte"); benchmark de preset no aparelho; VFR/30000-1001/offset A-V; detecção de HDR/10-bit; capítulos/extras/rotação após edição. Diga também **o que pode ser decidido sem teste adicional**.

**(D) Lista explícita do que você decide NÃO mudar** (diferenças de plataforma que devem ser apenas documentadas) e por quê.

**(E) Riscos e o que pode dar errado** em cada fase, com a mitigação.

## 7. Restrições

- Não invente resultados: todo número citado deve vir deste prompt ou de execução que você fizer; diga de onde veio.
- O uso é real (material de evidência): nenhuma mudança pode degradar codec, resolução, canais, faixas ou timeline **em silêncio**; quando algo não puder ser cumprido, o app deve explicar e recusar ou oferecer a alternativa.
- Não embuta chaves de API; não commite nem publique artefatos; não altere os aplicativos antes da autorização — sua entrega é **o plano e as decisões**.
- Convenções dos repositórios: no Android, todo fonte de produção precisa de linha no `MODULE-MAP.md` e KDoc; o commit passa por um gate/pre-commit (nunca use `--no-verify`). No Windows, os testes vivem em `tests/` e o painel é o arquivo grande `src/ffmpeg_tools_panel.py` (evite refatorações amplas — extraia só o necessário).
