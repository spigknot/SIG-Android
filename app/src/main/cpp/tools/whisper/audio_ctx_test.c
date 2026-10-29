/*
 * R15/D2 — audio_ctx derivado da janela (função pura, sem device).
 *
 * Premissa: 16 kHz, hop 160 (10 ms), 2 quadros de mel por posição => /320.
 * Sanidade: 30 s = 480.000 amostras -> 1.500 posições = n_audio_ctx padrão.
 */
#include <stdio.h>

#include "sig_audio_ctx.h"

static int falhas = 0;
static int casos = 0;

static void check(const char * rot, long long n, int modelo, int esperado) {
    const int obtido = sig_audio_ctx_de_janela(n, modelo);
    const int ok = (obtido == esperado);
    casos++;
    if (!ok) falhas++;
    printf("  %-42s amostras=%-7lld modelo=%-5d esperado=%-5d obtido=%-5d %s\n",
           rot, n, modelo, esperado, obtido, ok ? "OK" : "FALHA");
}

int main(void) {
    printf("== R15/D2 audio_ctx de janela ==\n");

    printf("-- janelas curtas (redução real) --\n");
    /* 1 s = 16000 -> 50 pos -> +7 = 57 */
    check("1 s", 16000, 1500, 57);
    /* 5 s = 80000 -> 250 pos -> +32 = 282 */
    check("5 s", 80000, 1500, 282);
    /* 10 s = 160000 -> 500 pos -> +63 = 563 */
    check("10 s", 160000, 1500, 563);

    printf("-- ceil cobre TODAS as amostras --\n");
    /* 320 exatos -> 1 pos + 1 margem = 2 */
    check("exatamente 320", 320, 1500, 2);
    /* 321 -> 2 pos (ceil) + 1 = 3 : uma amostra a mais NÃO pode ser ignorada */
    check("321 (1 amostra além)", 321, 1500, 3);
    check("639 -> 2 pos", 639, 1500, 3);
    check("640 -> 2 pos", 640, 1500, 3);
    check("641 -> 3 pos", 641, 1500, 4);

    printf("-- limites do modelo --\n");
    /* 30 s inteiros: 1500 pos + 188 = 1688 > 1500 -> RECUSA (0) */
    check("30 s excede o modelo", 480000, 1500, 0);
    /* janela máxima que ainda cabe: pos=1333 -> 1333+167=1500 */
    check("maior janela que cabe", 1333LL * 320, 1500, 1500);

    printf("-- entradas inválidas --\n");
    check("zero amostras", 0, 1500, 0);
    check("negativo", -1, 1500, 0);
    check("modelo inválido", 16000, 0, 0);

    printf("-- a recusa NÃO devolve número mágico --\n");
    {
        const int r = sig_audio_ctx_de_janela(480000, 1500);
        casos++;
        if (r != 0) { falhas++; printf("  FALHA: recusa devolveu %d (esperado 0)\n", r); }
        else printf("  recusa => 0 (default do modelo), nunca 1024/1500 fixo  OK\n");
    }

    printf("== RESULTADO: %d casos, %d falhas ==\n", casos, falhas);
    return falhas == 0 ? 0 : 1;
}
