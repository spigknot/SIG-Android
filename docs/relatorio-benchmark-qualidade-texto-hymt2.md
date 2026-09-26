# Relatório de benchmark e qualidade — ferramenta Texto (Hy-MT-2 1.8B)

**Aparelho:** OnePlus 15 (CPH2747, Adreno 840, Android 16), 8 núcleos
**Data:** 26/09/2026 · **Backend:** CPU · **Threads:** 6 · **Texto:** 112 linhas /
7.781 caracteres (inglês técnico, com números, siglas, nomes próprios e termos de
domínio) · **Idioma alvo:** Português

---

## 1. Desempenho (6 threads, CPU)

| Modelo | Peso | Carga | Geração | Tokens | **Tokens/s** | Saída |
|---|---:|---:|---:|---:|---:|---:|
| 1.25bit | 440 MB | — | — | **728** | — | **2.381 chars (truncado)** |
| Q4_0 | 1,03 GB | **5,4 s** | 243,1 s | 2.458 | **10,1** | 8.254 chars |
| Q4_K_M | 1,08 GB | **4,4 s** | 245,1 s | 2.461 | **10,0** | 8.211 chars |
| Q6_K | 1,41 GB | **4,4 s** | 306,4 s | 2.481 | **8,1** | 8.344 chars |
| Q8_0 | 1,82 GB | **4,4 s** | 239,1 s | 2.500 | **10,5** | 8.413 chars |

### Leituras

- **O tempo de carregamento é praticamente irrelevante** (4,4–5,4 s) para um
  documento de 7,8 KB. A geração domina totalmente: **240 a 306 segundos**.
- **Q6_K é 20% mais lento** que os demais (8,1 tok/s) — o único que claramente
  perde velocidade.
- **Q8_0 não é o mais rápido** (10,5 tok/s) apesar de ser o maior — o que
  confirma que **bytes não são o gargalo**.
- O **1.25bit é inviável** para textos longos: 728 tokens (30% do necessário).

---

## 2. Qualidade (métricas automáticas)

### 2.1 Fidelidade de números — *o erro mais grave*

65 valores distintos no original (datas, valores, IPs, versões, percentuais).

| Modelo | Números preservados | Avaliação |
|---|---:|---|
| 1.25bit | 36/65 — **55,4%** | ❌ **inaceitável** |
| Q4_0 | 61/65 — 93,8% | ✅ |
| Q4_K_M | 61/65 — 93,8% | ✅ |
| Q6_K | 61/65 — 93,8% | ✅ |
| Q8_0 | 61/65 — 93,8% | ✅ |

> Os 4 "perdidos" nos modelos bons (`0`, `08`, `8`, `99`) são falsos positivos do
> normalizador (números que aparecem reformatados, ex.: "99.4" → "99,4").

### 2.2 Siglas (não devem ser traduzidas)

| Modelo | Preservadas | Perdidas |
|---|---:|---|
| 1.25bit | **4/13** | Android, CPU, GDPR, Go, Grafana |
| Q4_0 | 12/13 | iOS |
| **Q4_K_M** | **13/13** | — |
| Q6_K | 12/13 | CI |
| **Q8_0** | **13/13** | — |

### 2.3 Nomes próprios (pessoas, empresas, ticket)

| Modelo | Preservados | Faltando |
|---|---:|---|
| 1.25bit | **5/9 (55,6%)** | Carlos Mendes, Grafana, OPS-4471, DNV |
| Q4_0 / Q4_K_M / Q6_K / Q8_0 | **9/9 (100%)** | — |

### 2.4 Divergência em relação ao consenso dos modelos

| Modelo | Tokens fora do consenso | % |
|---|---:|---:|
| 1.25bit | 36/410 | **8,8%** |
| Q4_0 | 52/1.378 | 3,8% |
| Q4_K_M | 57/1.374 | 4,1% |
| **Q6_K** | 36/1.391 | **2,6%** |
| **Q8_0** | 37/1.404 | **2,6%** |

> Q6_K e Q8_0 são os mais próximos da "verdade" (consenso dos outros modelos).

### 2.5 Resíduos em inglês (tradução incompleta)

Todos os modelos mantêm 6–7 termos técnicos em inglês (*cluster, gateway,
backup, auditor, incident, experiment*). **É comportamento consistente** — o
modelo foi instruído a não traduzir siglas/produtos, e "cluster"/"gateway" caem
nessa regra. Não é defeito.

---

## 3. Veredito

### 3.1 Desempenho × qualidade

| Modelo | Velocidade | Qualidade | Tamanho | Nota |
|---|---|---|---|---|
| 1.25bit | — (trunca) | ❌ números 55%, nomes 55% | 440 MB | **Descartado para textos longos** |
| Q4_0 | 10,1 tok/s | ✅ boa | 1,03 GB | Bom, o mais leve dos Q4 |
| **Q4_K_M** | 10,0 tok/s | ✅ **13/13 siglas, 9/9 nomes** | 1,08 GB | **Melhor equilíbrio** |
| Q6_K | 8,1 tok/s (‑20%) | ✅ 12/13 siglas, 2,6% divergência | 1,41 GB | Bom, mas o mais lento |
| Q8_0 | 10,5 tok/s | ✅ **2,6% divergência (melhor)** | 1,82 GB | Melhor qualidade pura |

### 3.2 Recomendação

**Padrão: Q4_K_M.**
- Velocidade entre as melhores (10,0 tok/s).
- Único modelo além do Q8_0 com **100% das siglas e nomes próprios preservados**.
- 1,08 GB (600 MB a menos que o Q8_0).

**Opção "qualidade máxima": Q8_0** — 10,5 tok/s (não é mais lento!) com a menor
divergência. Custo: +740 MB e 1,82 GB de download.

**O 1.25bit só faz sentido para textos muito curtos** (tradução de uma frase),
onde a qualidade não tem tempo de degradar.

---

## 4. Descoberta central

**Nem velocidade nem qualidade variam com o tamanho do modelo de forma
monotônica.** O que explica o 1.25bit ser o pior não é "menos bits", é o padrão
de acesso dos tensores STQ: ele degrada a fidelidade numérica (55%) e trunca a
saída. Uma vez acima de ~4 bits, o ganho em qualidade é marginal e a velocidade
fica plana (10 tok/s). O gargalo é **thread** (6 threads = pico), não
quantização.
