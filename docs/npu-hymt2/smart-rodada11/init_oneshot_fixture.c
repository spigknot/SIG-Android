/* R10: fixture OFFLINE do init one-shot (semantica do ggml_hexagon_init).
   Regra: os opts sao lidos UMA vez no primeiro registro; configurar DEPOIS
   = LATE (ignorado); configurar ANTES = consumido. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int  opt_nhvx = 0;          /* default */
static int  opt_consumido = 0;     /* valor efetivo apos o init */
static int  initialized = 0;       /* one-shot */
static const char * env_nhvx = NULL;  /* "setenv" simulado */

static void ggml_hexagon_init(void) {
    if (initialized) return;
    opt_consumido = (env_nhvx != NULL) ? atoi(env_nhvx) : opt_nhvx;
    initialized = 1;
}
static int erro = 0;
static void check(int c, const char * m) { printf(c ? "ok: %s\n" : "FAIL: %s\n", m); if (!c) erro++; }

int main(void) {
    /* caso 1: configurar ANTES (pre) => consumido */
    env_nhvx = "8";
    ggml_hexagon_init();
    check(opt_consumido == 8, "PRE: valor nao-default consumido no ponto real (8)");

    /* caso 2: configurar DEPOIS (late) => LATE/ineficaz */
    env_nhvx = "4";
    ggml_hexagon_init();          /* one-shot: no-op */
    check(opt_consumido == 8, "LATE: alteracao pos-registro NAO muda o efetivo (segue 8)");

    /* caso 3: sem env => default */
    initialized = 0; env_nhvx = NULL;
    ggml_hexagon_init();
    check(opt_consumido == 0, "default: sem env => default do fonte (0)");

    printf(erro ? "RESULTADO: %d FALHAS\n" : "RESULTADO: TODOS PASS\n", erro);
    return erro ? 1 : 0;
}
