/*
 * R15/C1 — TABELA DE ESTADOS do modo R6.
 *
 * Inclui o MESMO header que o backend Vulkan (`ggml-vulkan-fa-r6-mode.h`).
 * Não há reimplementação aqui: se o parser mudar, este teste acompanha.
 *
 * Cobre a tabela exigida pela ordem:
 *   genérica falhou / R6 compilou / R6 não compilou / guardou recusa
 *   × auto / force / off / vazio / ausente
 *
 * A parte de "compilou / guardou recusa" é a consulta de DISPONIBILIDADE
 * (mode_ok), que depende de GPU — fica nas células de aparelho. Aqui está a
 * parte PURA: preferência (env) -> modo, e a regra de que preferência NÃO é
 * disponibilidade.
 *
 * Puro C: roda no aparelho sem libc++ e sem nenhuma dependência de Android.
 */
#include <stdio.h>
#include <string.h>

#include "ggml-vulkan-fa-r6-mode.h"

static int falhas = 0;
static int casos  = 0;

static void check(const char * rotulo, const char * env, int esperado) {
    const int obtido = ggml_vk_fa_r6_parse_mode(env);
    const int ok = (obtido == esperado);
    casos++;
    if (!ok) falhas++;
    printf("  %-22s env=%-12s esperado=%-5s obtido=%-5s %s\n",
           rotulo,
           env == NULL ? "(null)" : (env[0] == '\0' ? "\"\"" : env),
           ggml_vk_fa_r6_mode_name(esperado),
           ggml_vk_fa_r6_mode_name(obtido),
           ok ? "OK" : "FALHA");
}

int main(void) {
    printf("== R15/C1 tabela de estados do modo R6 ==\n");

    /* ---- ausente / vazio: AUTO (nunca "desligado") ---- */
    check("ausente",              NULL,  GGML_FA_R6_AUTO);
    check("vazio",                "",    GGML_FA_R6_AUTO);

    /* ---- desligado explícito ---- */
    check("zero",                 "0",   GGML_FA_R6_OFF);
    check("off literal",          "off", GGML_FA_R6_OFF);

    /* ---- forçado ---- */
    check("um",                   "1",     GGML_FA_R6_FORCE);
    check("force literal",        "force", GGML_FA_R6_FORCE);

    /* ---- desconhecido: AUTO, NUNCA desliga ---- */
    check("dois",                 "2",   GGML_FA_R6_AUTO);
    check("true",                 "true",GGML_FA_R6_AUTO);
    check("abc",                  "abc", GGML_FA_R6_AUTO);
    check("OFF maiusculo",        "OFF", GGML_FA_R6_AUTO);
    check("espaco a esquerda",    " off",GGML_FA_R6_AUTO);
    check("prefixo ambiguo",      "autoforce", GGML_FA_R6_AUTO);
    check("so espaco",            " ",   GGML_FA_R6_AUTO);

    /* ---- REGRESSÃO R14: os três casos que o atoi() lia como 0 ---- */
    printf("-- regressao R14 (atoi transformava em 0) --\n");
    {
        const char * bugados[] = { "force", "", "abc" };
        for (int i = 0; i < 3; i++) {
            const int m = ggml_vk_fa_r6_parse_mode(bugados[i]);
            const int ok = (m != GGML_FA_R6_OFF);
            casos++;
            if (!ok) {
                falhas++;
                printf("  REGRESSAO env='%s' virou OFF (era o bug do atoi)\n", bugados[i]);
            } else {
                printf("  REGRESSAO env='%-6s' -> %-5s (nao OFF) OK\n",
                       bugados[i], ggml_vk_fa_r6_mode_name(m));
            }
        }
    }

    /* ---- "off" é o ÚNICO caminho textual para OFF, junto com "0" ---- */
    printf("-- coerencia: so '0' e 'off' desligam --\n");
    {
        const char * todos[] = { NULL, "", "0", "off", "1", "force", "2", "true",
                                 "abc", "OFF", " off", " ", "autoforce", "00", "0x0" };
        int offs = 0;
        for (unsigned i = 0; i < sizeof(todos) / sizeof(todos[0]); i++) {
            if (ggml_vk_fa_r6_parse_mode(todos[i]) == GGML_FA_R6_OFF) offs++;
        }
        const int ok = (offs == 2);
        casos++;
        if (!ok) falhas++;
        printf("  entradas que desligam = %d (esperado 2) %s\n", offs, ok ? "OK" : "FALHA");
    }

    /* ---- DISPONIBILIDADE: preferência NÃO é disponibilidade ----
     *
     * Matriz completa: modo (off/auto/force) × pipelines presentes × compilou
     * × falhou. Esperado = usado pela produção (ggml_vk_fa_r6_mode_ok):
     *   disponível <=> modo != off  E  presentes  E  compilados  E  não falhou
     */
    printf("-- matriz de disponibilidade (generica falhou / R6 estado) --\n");
    {
        const int modos[3] = { GGML_FA_R6_OFF, GGML_FA_R6_AUTO, GGML_FA_R6_FORCE };
        const char * nomes[3] = { "off", "auto", "force" };
        int combinacoes = 0, disponiveis = 0, erros = 0;

        for (int mi = 0; mi < 3; mi++) {
            for (int present = 0; present <= 1; present++) {
                for (int compiled = 0; compiled <= 1; compiled++) {
                    for (int failed = 0; failed <= 1; failed++) {
                        const int esperado = (modos[mi] != GGML_FA_R6_OFF &&
                                              present && compiled && !failed) ? 1 : 0;
                        const int obtido = ggml_fa_r6_available_from(
                            modos[mi], present, compiled, failed);
                        combinacoes++;
                        if (esperado) disponiveis++;
                        if (obtido != esperado) {
                            erros++;
                            printf("    FALHA modo=%s present=%d compiled=%d failed=%d"
                                   " esperado=%d obtido=%d\n",
                                   nomes[mi], present, compiled, failed,
                                   esperado, obtido);
                        }
                    }
                }
            }
        }
        casos++;
        if (erros) falhas++;
        printf("  %d combinacoes, %d disponiveis, %d erros %s\n",
               combinacoes, disponiveis, erros, erros == 0 ? "OK" : "FALHA");
    }

    /* ---- o caso concreto do PJA110: genérica falhou, R6 compilou ---- */
    printf("-- cenarios do contrato --\n");
    {
        struct {
            const char * nome;
            int mode, present, compiled, failed, esperado;
        } cen[] = {
            { "generic falhou + R6 compilou (auto)",  GGML_FA_R6_AUTO,  1, 1, 0, 1 },
            { "generic falhou + R6 NAO compilou",     GGML_FA_R6_AUTO,  1, 0, 0, 0 },
            { "R6 guardou recusa (failed)",           GGML_FA_R6_AUTO,  1, 0, 1, 0 },
            { "off explicito, pipelines ok",          GGML_FA_R6_OFF,   1, 1, 0, 0 },
            { "force, pipelines ok",                  GGML_FA_R6_FORCE, 1, 1, 0, 1 },
            { "auto, pipelines ausentes",             GGML_FA_R6_AUTO,  0, 0, 0, 0 },
        };
        for (unsigned i = 0; i < sizeof(cen) / sizeof(cen[0]); i++) {
            const int obtido = ggml_fa_r6_available_from(
                cen[i].mode, cen[i].present, cen[i].compiled, cen[i].failed);
            const int ok = (obtido == cen[i].esperado);
            casos++;
            if (!ok) falhas++;
            printf("  %-38s esperado=%d obtido=%d %s\n",
                   cen[i].nome, cen[i].esperado, obtido, ok ? "OK" : "FALHA");
        }
    }

    printf("== %d casos, %d falhas ==\n", casos, falhas);
    return falhas == 0 ? 0 : 1;
}
