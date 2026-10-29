#ifndef GGML_VULKAN_FA_R6_MODE_H
#define GGML_VULKAN_FA_R6_MODE_H

/*
 * R15/C1 — MODO DA ROTA R6: fonte ÚNICA.
 *
 * Este header é incluído pelo backend Vulkan E pelo teste de tabela de estados.
 * É o "mesmo código do teste" — não uma reimplementação que poderia divergir.
 *
 * Motivo da existência: a R14 tinha DOIS parsers do mesmo env:
 *
 *   preflight (ggml_vk_load_shaders)      consulta (sig_vk_fa_r6_available)
 *   -----------------------------------   ------------------------------------
 *   ausente            -> 1 (auto)        ausente      -> 1
 *   ""                 -> 1 (auto)        ""           -> 0 (DESLIGADO)  BUG
 *   "0" / "off"        -> 0               "0"/"off"    -> 0
 *   "1" / "force"      -> 2 (forçado)     "force"      -> 0 (DESLIGADO)  BUG
 *   "abc" (desconhec.) -> 1 (auto)        "abc"        -> 0 (DESLIGADO)  BUG
 *
 * Resultado: `GGML_VK_FA_R6=force` era forçado no preflight e reportado como
 * DESLIGADO na consulta de disponibilidade. Preferência e disponibilidade
 * precisam sair da MESMA leitura — e preferência NÃO é disponibilidade.
 *
 * Autocontido de propósito (sem <string.h>) para poder ser compilado em
 * qualquer toolchain de teste, inclusive o do device.
 */

#define GGML_FA_R6_OFF   0   /* desligado por decisão explícita        */
#define GGML_FA_R6_AUTO  1   /* usa quando a família genérica falhou   */
#define GGML_FA_R6_FORCE 2   /* entra sempre que o shape casar         */

static inline int ggml_fa_r6_str_eq(const char * a, const char * b) {
    while (*a != '\0' && *a == *b) {
        ++a;
        ++b;
    }
    return *a == *b;
}

/*
 * Traduz o valor de GGML_VK_FA_R6 no modo.
 * Ausente, vazio ou desconhecido = AUTO. Apenas "0"/"off" desligam.
 */
static inline int ggml_vk_fa_r6_parse_mode(const char * env_r6) {
    if (env_r6 == 0 || env_r6[0] == '\0') return GGML_FA_R6_AUTO;
    if (ggml_fa_r6_str_eq(env_r6, "0") || ggml_fa_r6_str_eq(env_r6, "off")) {
        return GGML_FA_R6_OFF;
    }
    if (ggml_fa_r6_str_eq(env_r6, "1") || ggml_fa_r6_str_eq(env_r6, "force")) {
        return GGML_FA_R6_FORCE;
    }
    return GGML_FA_R6_AUTO;
}

/*
 * DISPONIBILIDADE a partir de fatos OBSERVADOS — pura, sem GPU.
 *
 * Preferência (env) NÃO é disponibilidade: `mode` só diz o que foi pedido.
 * A rota só é utilizável quando os dois pipelines R6 existem, COMPILARAM e
 * nenhum falhou. Caso contrário o chamador tem de recusar/recarregar com FAoff
 * em vez de acreditar que existe fallback.
 *
 *   mode          pipelines_present  compiled  failed  ->  disponivel
 *   off                   *             *        *           0
 *   auto/force            0             *        *           0
 *   auto/force            1             0        *           0
 *   auto/force            1             *        1           0
 *   auto/force            1             1        0           1
 */
static inline int ggml_fa_r6_available_from(int mode, int pipelines_present,
                                            int compiled, int failed) {
    if (mode == GGML_FA_R6_OFF) return 0;
    if (!pipelines_present)     return 0;
    if (failed)                 return 0;
    if (!compiled)              return 0;
    return 1;
}

/* Nome estável do modo, para log e diagnóstico. */
static inline const char * ggml_vk_fa_r6_mode_name(int mode) {
    switch (mode) {
        case GGML_FA_R6_OFF:   return "off";
        case GGML_FA_R6_FORCE: return "force";
        default:               return "auto";
    }
}

#endif /* GGML_VULKAN_FA_R6_MODE_H */
