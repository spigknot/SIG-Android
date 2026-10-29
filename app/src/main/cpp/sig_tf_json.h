// R8/fase A (A4) — emissão de float para JSON VÁLIDO nos dumps TF (DIAG).
//
// Defeito corrigido: os passos TF formatavam `plog=%.9g` cru — com logits
// MASCARADOS o logprob do token é -inf e o snprintf escrevia `"plog":-inf`,
// que não é JSON: o validador/comparador pulava a linha (sequência incompleta
// aprovada) e o passo desaparecia da prova.
//
// Contrato: não finito vira `null` + `<campo>_class` com a CLASSE exata
// ("-inf"/"+inf"/"nan"). O valor cru continua recuperável (hashes raw/proc da
// MESMA linha + dumps binários), a linha NUNCA é removida e finitos não mudam
// de forma (`%.9g`, idêntico ao histórico — compat do comparador).
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdio>

inline void sig_tf_json_float(const char * field, double v, char * out, std::size_t cap) {
    if (std::isfinite(v)) {
        std::snprintf(out, cap, "\"%s\":%.9g", field, v);
    } else {
        const char * cls = std::isnan(v) ? "nan" : (v < 0.0 ? "-inf" : "+inf");
        std::snprintf(out, cap, "\"%s\":null,\"%s_class\":\"%s\"", field, field, cls);
    }
}
