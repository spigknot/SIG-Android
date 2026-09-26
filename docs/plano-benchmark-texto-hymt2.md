# Plano de benchmark — ferramenta Texto (Hy-MT-2) no Android

## Por que este plano

Medido no Ace 2 Pro (8 núcleos, Adreno 740): **1.25bit, Q4_0 e Q4_K_M deram ~16 tokens/s com o mesmo tempo**. Isso é a pista mais importante que temos, porque **descarta banda de memória como gargalo** — o Q4_K_M lê 2,4× mais bytes que o 1.25bit e não ficou mais lento.

O log mostrou `n_threads=4` com 8 núcleos disponíveis: **metade do aparelho ociosa**. A hipótese a testar é que o gargalo é o número de threads, não a quantização.

Consequência prática: se confirmada, trocar de quantização **não** ganha velocidade — e o caminho correto seria ir para **quantizações menos agressivas (mais bits)**, que melhoram a qualidade sem custo de tempo, até o ponto em que a velocidade cair.

## Hipóteses (o que o benchmark precisa responder)

| # | Hipótese | Como o teste responde |
|---|---|---|
| H1 | Threads é o gargalo (4 → 8 dá ganho) | Varredura de threads com modelo fixo |
| H2 |-after H1, quantização é nearly-livre | Curva tempo × bits (1.25bit → Q8_0) |
| H3 | GPU do Texto nunca é opção no Adreno 740 | Vulkan/OpenCL por modelo, no log |
| H4 | Mais bits = mais qualidade até saturar | Avaliação de qualidade (ver seção 5) |

## Fase 0 — Preparação (sem celular, eu já fiz)

- [x] Botão de threads na UI (`Auto (N núcleos)` / 4 / 6 / 8 / 12), persistido
- [x] Log mostra `Threads: N` na carga do modelo
- [ ] APK compilado e instalado
- [ ] Texto de benchmark padronizado (mesma frase em todos os testes)

## Fase 1 — Varredura de threads (a pergunta que bloqueia tudo)

Modelo **Q4_0**, CPU, mesmo texto. Medir: load, geração, tokens/s.

**Threads:** `Auto` (todos), 4, 6, 8

**Critério de decisão:**
- Se Auto/8 for **>20% mais rápido** que 4 → H1 confirmada: padrão do app vira "todos os núcleos" e seguimos.
- Se for igual → o gargalo é outro (provavelmente memória *ou* a GPU já está no caminho CPU) e a curva de quantização vira a prioridade.

> Automação: o script `bench_ace.py` (já escrito) faz a varredura e grava JSON. Falta só rodar com o aparelho acessível.

## Fase 2 — Curva tempo × bits (o que responde sua pergunta sobre qualidade)

Threads fixo no melhor valor da Fase 1. **CPU**.

**Ordem crescente de qualidade** (menor → maior bits por peso):

| Modelo | Bits/peso | Tamanho | Qualidade esperada |
|---|---|---|---|
| 1.25bit (STQ) | ~1,25 | 0,46 GB | mais grosseira |
| Q4_0 | 4,0 | 1,08 GB | boa |
| Q4_K_M | 4,8 | 1,13 GB | melhor que Q4_0 |
| Q5_0 | 5,0 | ~1,3 GB | melhor ainda |
| Q6_K | 6,0 | ~1,5 GB | quase sem perda |
| Q8_0 | 8,0 | ~1,9 GB | referência (perda mínima) |

**Critério de decisão:**
- Se tempo **não subir** de 1.25bit até Q6_K → **ganho puro de qualidade**:adotar Q6_K como novo padrão.
- Se tempo subir a partir de Q5/Q6 → **curva de custo-benefício**: publicar Q4_K_M como padrão e Q6_K como opção "qualidade".
- Q8_0 é a referência de qualidade (serve de teto: serve para confirmar que a escolha mais agressiva não custa qualidade).

## Fase 3 — GPU por modelo (esclarece o que já sabemos)

Ainda no mesmo aparelho, com o log de estatisticas:

| Modelo | CPU | Vulkan | OpenCL |
|---|---|---|---|
| 1.25bit | ✅ | mede (já sabem-se que não há kernel GPU) | mede |
| Q4_0 | ✅ | mede | mede |
| Q4_K_M | ✅ | mede | mede |

O critério não é só velocidade: é **produzir texto correto**. Já sabemos que no Adreno 740 o Vulkan não conclui (shader rejeitado) e o OpenCL não termina — então a Fase 3 é mais para **documentar** e medir os buffers/VRAM, do que para escolher o padrão.

## Fase 4 — Validação no OnePlus 15 (Adreno 840, driver novo)

Onde o Vulkan **funciona** (já confirmado com o Whisper). Repetir 1.25bit e Q4_0 em CPU/Vulkan/OpenCL para comparar com o Ace. Se a GPU for mais rápida **e** correta, vale a penaAMD Across.

## Fase 5 — Qualidade (heurística, sem gold standard)

Não temos ground-truth de tradução automática, então:
- **Comparação cruzada**: traduzir o mesmo texto com 1.25bit e Q8_0 e contar **divergências de tokens** (menor divergência = quantização mais próxima da referência).
- **Termos técnicos preservados**: nomes próprios, números, siglas (traduções ruins tendem a "traduzir" nomes).
- **Inspeção visual**: 3-5 frases com vocábulario difícil (termos técnicos de segurança, por exemplo).

## Fase 6 — Decisão e publicação

Documento com a matriz completa, e:
- **Novo padrão** definido com base nos dados
- Versão recomendada na nota de lançamento
- Atualização do `sig-android-dev` (skill) com a curva medida

## Execução (quando você tiver o celular acessível)

Eu rodo as Fases 1-3 em sequência automática, sem depender de toque na tela além de desbloquear o aparelho:

1. Confirmar que o aparelho está no adb (`adb devices`)
2. Instalar o APK com o botão de threads
3. `python bench_ace.py` — Fase 1 (threads) — Fase 2 (curva de bits)
4. Ler os resultados e decidir o novo padrão
5. Publicar

**Tempo estimado:** Fase 1 ~15 min, Fase 2 ~30 min (6 modelos × ~1 min), Fase 3 ~20 min. Tudo automatizado.

**Decisão que eu já tomaria** (sujeita à Fase 1): se threads for o gargalo e a curva for plana, **adotar Q6_K como padrão** — qualidade de 6 bits com a velocidade do 4 bits. Ou, se nem isso, ao menos **oferecer Q6_K como opção consciente** no menu de modelo.
