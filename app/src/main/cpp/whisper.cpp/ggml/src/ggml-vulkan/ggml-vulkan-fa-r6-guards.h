// R7/fase A (A3) — GUARDAS DE CONTRATO da variante compacta fa_r6 (FA Vulkan).
//
// Por que existe: até a R6, `ggml_vk_fa_r6_op_ok` validava só parcialmente
// (Q head_dim não conferido, dst sem layout efetivo, máscara indexada como
// densa sem checar strides/broadcast, pushes/grid sem overflow, capacidades de
// subgroup só por `subgroup_size==64`). A revisão da R6 reprovou o contrato.
//
// Este header concentra as decisões de RECUSA com razão específica, em C++
// puro (sem Vulkan/ggml), de modo que o MESMO código decide no produto
// (ggml-vulkan.cpp) e é exercitado pelo teste de host
// (tools/whisper/fa_r6_guard_test.cpp). Regras: só o que o shader fa_r6
// REALMENTE implementa passa; qualquer desvio é recusado ANTES do dispatch
// (ou, no caso de máscara, recusado se fora do layout denso/broadcast
// suportado). Um resultado inválido NUNCA pode virar sucesso nem abort.
#pragma once

#include <cstdint>
#include <string>

// Capacidades relevantes do device para CRIAR a pipeline fa_r6.
struct ggml_fa_r6_caps {
    uint32_t subgroup_size = 0;
    bool     subgroup_size_control = false;
    uint32_t subgroup_min_size = 0;
    uint32_t subgroup_max_size = 0;
    bool     subgroup_full_support = false;   // computeFullSubgroups
    uint32_t max_wg_count[3] = {0, 0, 0};     // maxComputeWorkGroupCount
};

// Shape/estilo de UM op FLASH_ATTN_EXT candidato ao caminho fa_r6.
// ne/nb como no ggml (nb em BYTES); sz_* = ggml_type_size(type).
struct ggml_fa_r6_shape {
    int64_t  ne_q[4] = {0, 0, 0, 0};  uint64_t nb_q[4] = {0, 0, 0, 0};
    int64_t  ne_k[4] = {0, 0, 0, 0};  uint64_t nb_k[4] = {0, 0, 0, 0};
    int64_t  ne_v[4] = {0, 0, 0, 0};  uint64_t nb_v[4] = {0, 0, 0, 0};
    int64_t  ne_o[4] = {0, 0, 0, 0};  uint64_t nb_o[4] = {0, 0, 0, 0};
    int64_t  ne_m[4] = {0, 0, 0, 0};  uint64_t nb_m[4] = {0, 0, 0, 0};
    uint32_t sz_q = 0, sz_k = 0, sz_v = 0, sz_o = 0, sz_m = 0;
    bool type_q_f32 = false, type_k_f16 = false, type_v_f16 = false;
    bool type_o_f32 = false, type_m_f16 = false;
    bool has_mask = false, has_sinks = false;
    float max_bias = 0.0f, logit_softcap = 0.0f;
};

// Verdadeiro quando o DEVICE suporta CRIAR a pipeline (64 lanes reais e
// enforçáveis). Razão específica em [why] quando falso.
inline bool ggml_fa_r6_creator_check(const ggml_fa_r6_caps & caps, std::string & why) {
    if (caps.subgroup_size != 64) {
        why = "subgroup_size != 64 (kernel exige 1 subgroup cheio de 64 lanes)";
        return false;
    }
    if (!caps.subgroup_size_control) {
        why = "sem VK_EXT_subgroup_size_control/subgroupSizeControl(eCompute) — requiredSubgroupSize=64 nao enforcavel";
        return false;
    }
    if (!(caps.subgroup_min_size <= 64 && 64 <= caps.subgroup_max_size)) {
        why = "requiredSubgroupSize=64 fora de [minSubgroupSize, maxSubgroupSize] do device";
        return false;
    }
    if (!caps.subgroup_full_support) {
        why = "computeFullSubgroups ausente (kernel assume subgroup CHEIO por workgroup)";
        return false;
    }
    return true;
}

// Verdadeiro quando ESTE shape casa com o contrato do fa_r6. Razão específica
// em [why] quando falso (preflight de família NÃO substitui isto: a decisão é
// POR OP/shape).
inline bool ggml_fa_r6_shape_check(const ggml_fa_r6_shape & s, const ggml_fa_r6_caps & caps, std::string & why) {
    // --- tipos exatos (o shader não tem variante para outro dtype)
    if (!s.type_q_f32) { why = "Q != F32"; return false; }
    if (!s.type_k_f16) { why = "K != F16"; return false; }
    if (!s.type_v_f16) { why = "V != F16"; return false; }
    if (!s.type_o_f32) { why = "dst != F32"; return false; }

    // --- dimensões completas (Q/K/V/dst) e D=64 para TODOS
    for (int i = 0; i < 4; ++i) {
        if (s.ne_q[i] < 1 || s.ne_k[i] < 1 || s.ne_v[i] < 1 || s.ne_o[i] < 1) {
            why = "dimensao vazia (ne < 1) em Q/K/V/dst";
            return false;
        }
    }
    if (s.ne_q[0] != 64 || s.ne_k[0] != 64 || s.ne_v[0] != 64 || s.ne_o[0] != 64) {
        why = "D != 64 (Q/K/V/dst head_dim)";
        return false;
    }
    if (s.ne_k[1] < 1 || s.ne_q[1] < 1) { why = "N/KV vazio"; return false; }
    if (s.ne_k[1] != s.ne_v[1]) { why = "KV de K != KV de V"; return false; }
    if (s.ne_q[2] != s.ne_k[2] || s.ne_q[2] != s.ne_v[2]) { why = "GQA (heads != heads_kv)"; return false; }
    if (s.ne_q[3] != s.ne_k[3] || s.ne_q[3] != s.ne_v[3]) { why = "batch != batch_kv"; return false; }

    // --- O = [D, heads, N, batch] e layout de escrita EFETIVO (o shader escreve
    // com fórmula CONTÍGUA fixa: d + head*D + row*heads*D + batch*N*heads*D)
    if (s.ne_o[1] != s.ne_q[2]) { why = "dst ne1 != heads"; return false; }
    if (s.ne_o[2] != s.ne_q[1]) { why = "dst ne2 != N (contrato ggml)"; return false; }
    if (s.ne_o[3] != s.ne_q[3]) { why = "dst ne3 != batch"; return false; }

    // --- recursos NÃO suportados continuam recusados
    if (s.has_sinks) { why = "sinks nao suportado"; return false; }
    if (s.max_bias != 0.0f) { why = "alibi(max_bias) != 0"; return false; }
    if (s.logit_softcap != 0.0f) { why = "logit_softcap != 0"; return false; }

    // --- strides/dtype/alinhamento: nb0 == tipo, divisões exatas do shader
    if (s.nb_q[0] != s.sz_q || s.nb_k[0] != s.sz_k || s.nb_v[0] != s.sz_v || s.nb_o[0] != s.sz_o) {
        why = "linhas nao contiguas (nb0 != type_size)";
        return false;
    }
    if ((s.nb_q[1] % 4) != 0 || (s.nb_q[2] % 4) != 0 || (s.nb_q[3] % 4) != 0) {
        why = "stride Q nao divisivel por 4 (q_stride = nb1/4 exato)";
        return false;
    }
    if ((s.nb_k[1] % 2) != 0 || (s.nb_k[2] % 2) != 0 || (s.nb_k[3] % 2) != 0 ||
        (s.nb_v[1] % 2) != 0 || (s.nb_v[2] % 2) != 0 || (s.nb_v[3] % 2) != 0) {
        why = "stride K/V nao divisivel por 2 (indice f16 exato)";
        return false;
    }
    if (s.nb_o[0] != s.sz_o || s.nb_o[1] != s.sz_o * (uint64_t) s.ne_o[0] ||
        s.nb_o[2] != s.nb_o[1] * (uint64_t) s.ne_o[1] ||
        s.nb_o[3] != s.nb_o[2] * (uint64_t) s.ne_o[2]) {
        why = "dst nao contiguo [D,heads,N,batch] (layout de escrita fixo do shader)";
        return false;
    }

    // --- overflow de push/grid: nada pode truncar em u32 nem estourar o device
    auto fits_u32 = [](int64_t v) { return v >= 0 && (uint64_t) v <= 0xFFFFFFFFull; };
    for (int i = 0; i < 4; ++i) {
        if (!fits_u32(s.ne_q[i]) || !fits_u32(s.ne_k[i]) || !fits_u32(s.ne_v[i]) || !fits_u32(s.ne_o[i])) {
            why = "ne nao cabe em u32 (push constants truncariam)";
            return false;
        }
        if (s.nb_q[i] > 0xFFFFFFFFull || s.nb_k[i] > 0xFFFFFFFFull ||
            s.nb_v[i] > 0xFFFFFFFFull || s.nb_o[i] > 0xFFFFFFFFull ||
            s.nb_m[i] > 0xFFFFFFFFull) {
            why = "nb nao cabe em u32 (push constants truncariam)";
            return false;
        }
    }
    if ((uint64_t) s.ne_q[1] > caps.max_wg_count[0] ||
        (uint64_t) s.ne_q[2] > caps.max_wg_count[1] ||
        (uint64_t) s.ne_q[3] > caps.max_wg_count[2]) {
        why = "grid (N,heads,batch) excede maxComputeWorkGroupCount do device";
        return false;
    }
    // offsets máximos que o shader calcula (índice do último elemento tocado)
    const uint64_t q_max = (s.nb_q[3] * (uint64_t) (s.ne_q[3] - 1) + s.nb_q[2] * (uint64_t) (s.ne_q[2] - 1)) / 4u
                           + s.nb_q[1] * (uint64_t) (s.ne_q[1] - 1) + (uint64_t) (s.ne_q[0] - 1);
    const uint64_t kv_max = (s.nb_k[3] * (uint64_t) (s.ne_k[3] - 1) + s.nb_k[2] * (uint64_t) (s.ne_k[2] - 1)) / 2u
                            + s.nb_k[1] * (uint64_t) (s.ne_k[1] - 1) + (uint64_t) (s.ne_k[0] - 1);
    const uint64_t vv_max = (s.nb_v[3] * (uint64_t) (s.ne_v[3] - 1) + s.nb_v[2] * (uint64_t) (s.ne_v[2] - 1)) / 2u
                            + s.nb_v[1] * (uint64_t) (s.ne_v[1] - 1) + (uint64_t) (s.ne_v[0] - 1);
    const uint64_t o_max = s.nb_o[3] * (uint64_t) (s.ne_o[3] - 1) / s.sz_o
                           + s.nb_o[2] * (uint64_t) (s.ne_o[2] - 1) / s.sz_o
                           + s.nb_o[1] * (uint64_t) (s.ne_o[1] - 1) / s.sz_o + (uint64_t) (s.ne_o[0] - 1);
    if (q_max > 0xFFFFFFFFull || kv_max > 0xFFFFFFFFull || vv_max > 0xFFFFFFFFull || o_max > 0xFFFFFFFFull) {
        why = "offset/span de buffer excede u32 (push constants truncariam)";
        return false;
    }

    // --- máscara (só o layout que o shader implementa): f16 DENSa nas dims
    // usadas, ne0=KV, ne1=N, broadcast ne2 em {1, heads} e ne3 em {1, batch};
    // índice em INTEIRO (uint) — o limite garante que não há wraparound.
    if (s.has_mask) {
        if (!s.type_m_f16) { why = "mascara != F16"; return false; }
        if (s.ne_m[0] < 1 || s.ne_m[1] < 1) { why = "mascara vazia (ne < 1)"; return false; }
        if (s.ne_m[0] != s.ne_k[1]) { why = "mascara ne0 != KV"; return false; }
        if (s.ne_m[1] != s.ne_q[1]) { why = "mascara ne1 != N"; return false; }
        const int64_t nem2 = s.ne_m[2], nem3 = s.ne_m[3];
        if (nem2 < 1 || nem3 < 1) { why = "mascara com ne2/ne3 invalido (<1)"; return false; }
        if (nem2 != 1 && nem2 != s.ne_q[2]) {
            why = "mascara ne2 fora de {1, heads} (broadcast nao suportado)";
            return false;
        }
        if (nem3 != 1 && nem3 != s.ne_q[3]) {
            why = "mascara ne3 fora de {1, batch} (broadcast nao suportado)";
            return false;
        }
        if (s.nb_m[0] != s.sz_m || s.nb_m[1] != s.sz_m * (uint64_t) s.ne_m[0]) {
            why = "mascara nao densa (nb0/nb1 != denso f16)";
            return false;
        }
        if (nem2 > 1 && s.nb_m[2] != s.nb_m[1] * (uint64_t) s.ne_m[1]) {
            why = "mascara nao densa (plano de head: nb2 != nb1*N)";
            return false;
        }
        if (nem3 > 1 && s.nb_m[3] != s.nb_m[2] * (uint64_t) nem2) {
            why = "mascara nao densa (plano de batch: nb3 != nb2*nem2)";
            return false;
        }
        const uint64_t idx_max = (((uint64_t) (nem3 - 1) * (uint64_t) nem2 + (uint64_t) (nem2 - 1)) *
                                  (uint64_t) s.ne_m[1] + (uint64_t) (s.ne_q[1] - 1)) * (uint64_t) s.ne_k[1]
                                 + (uint64_t) (s.ne_k[1] - 1);
        if (idx_max > 0x7FFFFFFFull) {
            why = "indice de mascara excede limite u32 (indice INTEIRO, sem float)";
            return false;
        }
    }

    why = "ok";
    return true;
}
