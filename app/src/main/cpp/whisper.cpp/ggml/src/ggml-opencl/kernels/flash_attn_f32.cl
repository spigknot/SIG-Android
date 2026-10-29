#pragma OPENCL EXTENSION cl_khr_fp16 : enable

#define ACC_TYPE float
#define ACC_TYPE4 float4
#define DATA_TYPE float
#define DATA_TYPE4 float4
#define MASK_DATA_TYPE half
#define CONVERT_ACC4(x) (x)
#define CONVERT_DATA4(x) (x)

#define DK_VEC (DK/4)
#define DV_VEC (DV/4)
#define WG_SIZE (BLOCK_M)
#define Q1_WG_SIZE 64
// R4/E2 (atualizado R6/C): kernels sao compilados com -cl-finite-math-only; um
// operando -INFINITY e UB no driver Adreno (exp() mal compilado => NaN) — o
// sentinel FINITO segue para running max/inicio (idioma do upstream llama.cpp).
// A R5/C1 DEMONSTROU que os NaN da R4 vinham de memoria local NAO INICIALIZADA
// no tail do tile (dot com l_k e p*V com l_v fora de [0, n_kv)), NAO do
// sentinel; o zero-fill (FA_TAIL_FILL) e a barreira de fim de consumo
// (FA_TILE_BARRIER) sao o contrato de memoria. Exclusao por mascara (-Inf) usa
// BIT-TEST exato (as_uint), nunca aritmetica com -INFINITY.
#define FA_M_INIT (-3.0e38f)
// Deteccao EXATA de -Inf por BITS: imune as otimizacoes de -cl-finite-math-only
// (comparacao aritmetica `x == -INFINITY` poderia ser dobrada para falso).
#define FA_BIAS_IS_NEG_INF(x) (as_uint(x) == 0xFF800000u)

// R5/fase C (C1): tail do tile com memoria DEFINIDA. Nos __local l_k/l_v, os
// slots fora de [0, n_kv) do ultimo tile ficavam NAO INICIALIZADOS e eram
// consumidos pelo compute (dot com l_k / p*V com l_v do tail). Aqui todo slot
// invalido do tile recebe carga DEFINIDA (zero), de modo que o tail contribui
// exatamente zero em V e nao ha leitura de memoria local indefinida.
// A R6/C mantem o zero-fill OBRIGATORIO mesmo com validade explicita:
// 0.0f * NaN = NaN — p=0 NAO substitui a carga definida do tile.
// Selecionavel apenas no build DIAG (-D FA_TAIL_FILL=0 reproduz o caso pre-fix);
// o build NORMAL compila sempre o caminho seguro (default 1).
#ifndef FA_TAIL_FILL
#define FA_TAIL_FILL 1
#endif

// R6/fase C (C1): barreira de fim de CONSUMO do tile. Fluxo por tile:
//   [carga cooperativa de l_k/l_v — TODAS as lanes]
//   barrier A (fim da carga)
//   compute do tile SOB PREDICADO (my_query_row < n_q)
//   barrier B (FIM DO CONSUMO — antes de qualquer store do proximo tile)
// Sem a barrier B, lanes inativas (my_query_row >= n_q) seguiam para a recarga
// do proximo tile enquanto as lanes ativas ainda liam o tile corrente:
// overwrite de memoria local (classe distinta do tail nao inicializado R5).
// Nenhum continue/return divergente pula barreira em iteracao ativa: o compute
// fica sob predicado e TODAS as lanes alcancam A e B em toda iteracao.
// Lockstep/subgroup NAO sao tratados como substituto deste contrato.
// FA_TILE_BARRIER=0 (DIAG) compila a variante pre-fix (mesma janela do R5)
// para o contraste; o build NORMAL compila sempre 1.
#ifndef FA_TILE_BARRIER
#define FA_TILE_BARRIER 1
#endif

// R6/fase C (C1) DIAG: atraso controlado do CONSUMO das lanes ativas — cadeia
// serial alimentada por dado real (Q/my_query_row) + guarda opaca em bits
// (nao eliminavel, resultado inalterado). Expõe o overwrite quando
// FA_TILE_BARRIER=0. NORMAL compila 0.
#ifndef FA_DELAY_CONSUME
#define FA_DELAY_CONSUME 0
#endif

inline float get_alibi_slope(
    const float max_bias, const uint h, const uint n_head_log2, const float m0, const float m1
) {
    if (max_bias <= 0.0f) {
        return 1.0f;
    }
    const float base = h < n_head_log2 ? m0 : m1;
    const int   exph = h < n_head_log2 ? h + 1 : 2*(h - n_head_log2) + 1;

    return pow(base, exph);
}
__kernel void flash_attn_f32(
    const global void * q_void, ulong q_offset,
    const global void * k_void, ulong k_offset,
    const global void * v_void, ulong v_offset,
    global void * o_void, ulong o_offset,
    const float scale,
    const int n_q,
    const int n_kv,
    const int is_causal,
    const int n_head,
    const ulong q_nb1, const ulong q_nb2, const ulong q_nb3,
    const ulong k_nb1, const ulong k_nb2, const ulong k_nb3,
    const ulong v_nb1, const ulong v_nb2, const ulong v_nb3,
    const ulong o_nb1, const ulong o_nb2, const ulong o_nb3,
    const float max_bias,
    const float m0,
    const float m1,
    const int n_head_log2,
    const float logit_softcap,
    const int n_head_kv,
    const global void* mask_void,
    const ulong mask_offset,
    const ulong mask_nb1,
    const ulong mask_nb2,
    const ulong mask_nb3,
    const int mask_ne2,
    const int mask_ne3,
    const global void* sinks_void,
    const ulong sinks_offset
) {
    const int tid = get_local_id(0);
    const int block_q_idx = get_group_id(0);
    const int head_batch_idx = get_global_id(1);

    const int my_query_row = block_q_idx * BLOCK_M + tid;

    const int batch_idx = head_batch_idx / n_head;
    const int head_idx = head_batch_idx % n_head;

    const int gqa_ratio = n_head / n_head_kv;
    const int head_kv_idx = head_idx / gqa_ratio;

    const global char* q_base = (const global char*)q_void + q_offset;
    const global char* k_base = (const global char*)k_void + k_offset;
    const global char* v_base = (const global char*)v_void + v_offset;
    global char* o_base = (global char*)o_void + o_offset;

    const global char* mask_base = NULL;
    if (mask_void != NULL) {
        const int mask_head_idx = head_idx % mask_ne2;
        const int mask_batch_idx = batch_idx % mask_ne3;
        mask_base = (const global char*)mask_void + mask_offset + mask_batch_idx * mask_nb3 + mask_head_idx * mask_nb2;
    }

    ACC_TYPE4 q_priv[DK_VEC];
    if (my_query_row < n_q) {
        const ulong q_row_offset = batch_idx * q_nb3 + head_idx * q_nb2 + my_query_row * q_nb1;
        const global DATA_TYPE4* q_ptr = (const global DATA_TYPE4*)(q_base + q_row_offset);
        #pragma unroll
        for (int i = 0; i < DK_VEC; ++i) {
            q_priv[i] = CONVERT_ACC4(q_ptr[i]);
        }
    }

    ACC_TYPE4 o_acc[DV_VEC];
    #pragma unroll
    for (int i = 0; i < DV_VEC; ++i) {
        o_acc[i] = (ACC_TYPE4)(0.0f);
    }
    ACC_TYPE m_i = FA_M_INIT;
    ACC_TYPE l_i = 0.0f;

    float slope = get_alibi_slope(max_bias, head_idx, n_head_log2, m0, m1);

    __local DATA_TYPE4 l_k[BLOCK_N][DK_VEC];
    __local DATA_TYPE4 l_v[BLOCK_N][DV_VEC];

    for (int k_start = 0; k_start < n_kv; k_start += BLOCK_N) {
        for (int i = tid; i < BLOCK_N * DK_VEC; i += WG_SIZE) {
            const int row = i / DK_VEC;
            const int col = i % DK_VEC;
            const int k_row_idx = k_start + row;
            if (k_row_idx < n_kv) {
                const ulong k_row_offset = batch_idx * k_nb3 + head_kv_idx * k_nb2 + k_row_idx * k_nb1;
                l_k[row][col] = ((__global DATA_TYPE4*)(k_base + k_row_offset))[col];
            }
#if FA_TAIL_FILL
            else {
                // R5/C1: slot fora de [0, n_kv) -> carga DEFINIDA (zero), para o
                // tail do tile nao consumir memoria local nao inicializada.
                l_k[row][col] = (DATA_TYPE4)(0.0f);
            }
#endif
        }
        for (int i = tid; i < BLOCK_N * DV_VEC; i += WG_SIZE) {
            const int row = i / DV_VEC;
            const int col = i % DV_VEC;
            const int v_row_idx = k_start + row;
            if (v_row_idx < n_kv) {
                const ulong v_row_offset = batch_idx * v_nb3 + head_kv_idx * v_nb2 + v_row_idx * v_nb1;
                l_v[row][col] = ((__global DATA_TYPE4*)(v_base + v_row_offset))[col];
            }
#if FA_TAIL_FILL
            else {
                l_v[row][col] = (DATA_TYPE4)(0.0f);
            }
#endif
        }
        barrier(CLK_LOCAL_MEM_FENCE);   // A: fim da CARGA do tile (todas as lanes)

        if (my_query_row < n_q) {
#if FA_DELAY_CONSUME > 0
            // R6/C1 DIAG (FA_DELAY_CONSUME>0): atraso serial do consumo; guarda
            // opaca em bits impede a eliminacao do laco; resultado inalterado.
            {
                ACC_TYPE spin = q_priv[0].s0 + (ACC_TYPE)(my_query_row + 1);
                #pragma unroll 1
                for (int dl = 0; dl < FA_DELAY_CONSUME; ++dl) {
                    spin = mad(spin, 1.0000001192f, 9.5367431640625e-07f);
                }
                if (as_uint(spin) == 0xDEADBEEFu) {
                    o_acc[0] = (ACC_TYPE4)(spin);
                }
            }
#endif
            for (int j = 0; j < BLOCK_N; j += 2) {
                const int k_row0 = k_start + j;
                const int k_row1 = k_start + j + 1;

                ACC_TYPE4 dot_acc0 = (ACC_TYPE4)(0.0f);
                ACC_TYPE4 dot_acc1 = (ACC_TYPE4)(0.0f);
                #pragma unroll
                for (int k = 0; k < DK_VEC; k++) {
                    dot_acc0 = mad(q_priv[k], CONVERT_ACC4(l_k[j][k]), dot_acc0);
                    dot_acc1 = mad(q_priv[k], CONVERT_ACC4(l_k[j+1][k]), dot_acc1);
                }
                ACC_TYPE score0 = (dot_acc0.s0 + dot_acc0.s1 + dot_acc0.s2 + dot_acc0.s3) * scale;
                ACC_TYPE score1 = (dot_acc1.s0 + dot_acc1.s1 + dot_acc1.s2 + dot_acc1.s3) * scale;

                // R6/C2: validade EXPLICITA por key (nunca inferida do valor
                // numerico do score). Causal/padding excluem independentemente do
                // sentinel; mask -Inf e deteccao por BITS.
                bool valid0 = (k_row0 < n_kv);
                bool valid1 = (k_row1 < n_kv);
                if (is_causal) {
                    const int k_lim = n_kv - n_q + my_query_row;
                    if (k_row0 > k_lim) valid0 = false;
                    if (k_row1 > k_lim) valid1 = false;
                }

                // R6/C2: softcap sobre o score VALIDO e ANTES da mascara
                // (contrato ggml-cpu ops.cpp FLASH_ATTN_EXT: s=dot*scale;
                // s=softcap*tanh(s/softcap); s+=mask).
                if (logit_softcap > 0.0f) {
                    score0 = logit_softcap * tanh(score0 / logit_softcap);
                    score1 = logit_softcap * tanh(score1 / logit_softcap);
                }

                ACC_TYPE bias0 = (ACC_TYPE)(0.0f);
                ACC_TYPE bias1 = (ACC_TYPE)(0.0f);
                if (mask_base != NULL) {
                    const global MASK_DATA_TYPE* mask_ptr = (const global MASK_DATA_TYPE*)(mask_base + my_query_row * mask_nb1);
                    // leituras ESTRITAMENTE dentro de [0, n_kv): nada de OOB
                    if (k_row0 < n_kv) {
                        bias0 = slope * (ACC_TYPE)mask_ptr[k_row0];
                        if (FA_BIAS_IS_NEG_INF(bias0)) { valid0 = false; bias0 = (ACC_TYPE)(0.0f); }
                    }
                    if (k_row1 < n_kv) {
                        bias1 = slope * (ACC_TYPE)mask_ptr[k_row1];
                        if (FA_BIAS_IS_NEG_INF(bias1)) { valid1 = false; bias1 = (ACC_TYPE)(0.0f); }
                    }
                }
                if (valid0) score0 += bias0;
                if (valid1) score1 += bias1;

                // running max apenas com keys VALIDAS; key invalida nao soma p no
                // denominador nem desloca m (pre-fix: sentinel/softcap virava o
                // proprio maximo finito => exp(0)=1 para key invalida).
                ACC_TYPE m_new = m_i;
                if (valid0 && score0 > m_new) m_new = score0;
                if (valid1 && score1 > m_new) m_new = score1;

                ACC_TYPE p0 = (ACC_TYPE)(0.0f);
                ACC_TYPE p1 = (ACC_TYPE)(0.0f);
                if (valid0) p0 = exp(score0 - m_new);
                if (valid1) p1 = exp(score1 - m_new);
                // nenhuma key valida vista => m_new == m_i (identidade) e p=0;
                // primeira key valida em tile posterior => m_i e sentinel finito
                // e exp(m_i - m_new) underflow a 0 (o/l sao 0 nesse ponto).
                const ACC_TYPE scale_prev = exp(m_i - m_new);

                #pragma unroll
                for (int i = 0; i < DV_VEC; ++i) {
                    o_acc[i] = o_acc[i] * scale_prev + p0 * CONVERT_ACC4(l_v[j][i]) + p1 * CONVERT_ACC4(l_v[j+1][i]);
                }
                l_i = l_i * scale_prev + p0 + p1;
                m_i = m_new;
            }
        }

#if FA_TILE_BARRIER
        // B: FIM DO CONSUMO do tile — todas as lanes (ativas e inativas) passam
        // por aqui antes de qualquer store do proximo tile (nenhum continue).
        barrier(CLK_LOCAL_MEM_FENCE);
#endif
    }

    if (my_query_row < n_q) {
        if (sinks_void != NULL) {
            const global ACC_TYPE* sinks_ptr = (const global ACC_TYPE*)((const global char*)sinks_void + sinks_offset);
            const ACC_TYPE m_sink = sinks_ptr[head_idx];
            const ACC_TYPE m_final = max(m_i, m_sink);

            const ACC_TYPE scale_o = exp(m_i - m_final);
            #pragma unroll
            for (int i = 0; i < DV_VEC; ++i) {
                o_acc[i] *= scale_o;
            }

            l_i = l_i * exp(m_i - m_final) + exp(m_sink - m_final);
        }

        const ulong o_row_offset = batch_idx * o_nb3 + my_query_row * o_nb2 + head_idx * o_nb1;
        global DATA_TYPE4 *o_row = (global DATA_TYPE4 *)(o_base + o_row_offset);
        if (l_i > 0.0f) {
            const ACC_TYPE l_inv = 1.0f / l_i;
            #pragma unroll
            for (int i = 0; i < DV_VEC; ++i) {
                o_row[i] = CONVERT_DATA4(o_acc[i] * l_inv);
            }
        } else {
            // R6/C2 (contrato, ref ggml-cpu S==0 => S_inv=0): linha totalmente
            // mascarada sem sinks escreve ZERO — nao ha "NaN substituido por 0".
            #pragma unroll
            for (int i = 0; i < DV_VEC; ++i) {
                o_row[i] = (DATA_TYPE4)(0.0f);
            }
        }
    }
}

// R6/C (auditoria C1 do q1): o kernel q1 NAO tem __local de tile de K/V (sem
// carga/recarga -> nao existe a barreira A/B de C1); as reducoes usam barreiras
// em fluxo UNIFORME (branch `l_final > 0` e uniforme entre as lanes). O ajuste
// desta fase no q1 e o MESMO contrato C2 de mascara/softcap/validade.
__kernel void flash_attn_f32_q1(
    const global void * q_void, ulong q_offset,
    const global void * k_void, ulong k_offset,
    const global void * v_void, ulong v_offset,
    global void * o_void, ulong o_offset,
    const float scale,
    const int n_q,
    const int n_kv,
    const int is_causal,
    const int n_head,
    const ulong q_nb1, const ulong q_nb2, const ulong q_nb3,
    const ulong k_nb1, const ulong k_nb2, const ulong k_nb3,
    const ulong v_nb1, const ulong v_nb2, const ulong v_nb3,
    const ulong o_nb1, const ulong o_nb2, const ulong o_nb3,
    const float max_bias,
    const float m0,
    const float m1,
    const int n_head_log2,
    const float logit_softcap,
    const int n_head_kv,
    const global void* mask_void,
    const ulong mask_offset,
    const ulong mask_nb1,
    const ulong mask_nb2,
    const ulong mask_nb3,
    const int mask_ne2,
    const int mask_ne3,
    const global void* sinks_void,
    const ulong sinks_offset
) {
    const int tid = get_local_id(0);
    const int head_batch_idx = get_global_id(1);

    const int batch_idx = head_batch_idx / n_head;
    const int head_idx = head_batch_idx % n_head;

    const int gqa_ratio = n_head / n_head_kv;
    const int head_kv_idx = head_idx / gqa_ratio;

    const global char* q_base = (const global char*)q_void + q_offset;
    const global char* k_base = (const global char*)k_void + k_offset;
    const global char* v_base = (const global char*)v_void + v_offset;
    global char* o_base = (global char*)o_void + o_offset;

    const global char* mask_base = NULL;
    if (mask_void != NULL) {
        const int mask_head_idx = head_idx % mask_ne2;
        const int mask_batch_idx = batch_idx % mask_ne3;
        mask_base = (const global char*)mask_void + mask_offset + mask_batch_idx * mask_nb3 + mask_head_idx * mask_nb2;
    }

    ACC_TYPE4 q_priv[DK_VEC];
    const ulong q_row_offset = batch_idx * q_nb3 + head_idx * q_nb2;
    const global DATA_TYPE4* q_ptr = (const global DATA_TYPE4*)(q_base + q_row_offset);
    #pragma unroll
    for (int i = 0; i < DK_VEC; ++i) {
        q_priv[i] = CONVERT_ACC4(q_ptr[i]);
    }

    float slope = get_alibi_slope(max_bias, head_idx, n_head_log2, m0, m1);

    const global ACC_TYPE* sinks_ptr = NULL;
    if (sinks_void != NULL) {
        sinks_ptr = (const global ACC_TYPE*)((const global char*)sinks_void + sinks_offset);
    }

    ACC_TYPE m_i = (sinks_ptr != NULL) ? sinks_ptr[head_idx] : FA_M_INIT;
    for (int k_idx = tid; k_idx < n_kv; k_idx += Q1_WG_SIZE) {
        const ulong k_row_offset = batch_idx * k_nb3 + head_kv_idx * k_nb2 + k_idx * k_nb1;
        const global DATA_TYPE4* k_ptr = (const global DATA_TYPE4*)(k_base + k_row_offset);
        ACC_TYPE4 dot_acc = (ACC_TYPE4)(0.0f);
        #pragma unroll
        for (int k = 0; k < DK_VEC; k++) {
            dot_acc = mad(q_priv[k], CONVERT_ACC4(k_ptr[k]), dot_acc);
        }
        ACC_TYPE score = (dot_acc.s0 + dot_acc.s1 + dot_acc.s2 + dot_acc.s3) * scale;
        // R6/C2: validade explicita + ordem softcap->mascara (mesmo contrato do geral)
        bool valid = true;
        ACC_TYPE bias = (ACC_TYPE)(0.0f);
        if (mask_base != NULL) {
            const global MASK_DATA_TYPE* mask_ptr = (const global MASK_DATA_TYPE*)(mask_base);
            bias = slope * (ACC_TYPE)mask_ptr[k_idx];
            if (FA_BIAS_IS_NEG_INF(bias)) { valid = false; bias = (ACC_TYPE)(0.0f); }
        }
        if (valid) {
            if (logit_softcap > 0.0f) {
                score = logit_softcap * tanh(score / logit_softcap);
            }
            score += bias;
            m_i = max(m_i, score);
        }
    }

    __local ACC_TYPE local_m[Q1_WG_SIZE];
    local_m[tid] = m_i;
    barrier(CLK_LOCAL_MEM_FENCE);
    #pragma unroll
    for (int s = Q1_WG_SIZE / 2; s > 0; s >>= 1) {
        if (tid < s) local_m[tid] = max(local_m[tid], local_m[tid + s]);
        barrier(CLK_LOCAL_MEM_FENCE);
    }
    const ACC_TYPE m_final = local_m[0];

    ACC_TYPE4 o_acc[DV_VEC];
    #pragma unroll
    for (int i = 0; i < DV_VEC; ++i) o_acc[i] = (ACC_TYPE4)(0.0f);
    ACC_TYPE l_i = 0.0f;

    for (int k_idx = tid; k_idx < n_kv; k_idx += Q1_WG_SIZE) {
        const ulong k_row_offset = batch_idx * k_nb3 + head_kv_idx * k_nb2 + k_idx * k_nb1;
        const ulong v_row_offset = batch_idx * v_nb3 + head_kv_idx * v_nb2 + k_idx * v_nb1;
        const global DATA_TYPE4* k_ptr = (const global DATA_TYPE4*)(k_base + k_row_offset);
        const global DATA_TYPE4* v_ptr = (const global DATA_TYPE4*)(v_base + v_row_offset);
        ACC_TYPE4 dot_acc = (ACC_TYPE4)(0.0f);
        #pragma unroll
        for (int k = 0; k < DK_VEC; k++) {
            dot_acc = mad(q_priv[k], CONVERT_ACC4(k_ptr[k]), dot_acc);
        }
        ACC_TYPE score = (dot_acc.s0 + dot_acc.s1 + dot_acc.s2 + dot_acc.s3) * scale;
        // R6/C2: validade explicita; key invalida => p=0 (sem exp no denominador)
        bool valid = true;
        ACC_TYPE bias = (ACC_TYPE)(0.0f);
        if (mask_base != NULL) {
            const global MASK_DATA_TYPE* mask_ptr = (const global MASK_DATA_TYPE*)(mask_base);
            bias = slope * (ACC_TYPE)mask_ptr[k_idx];
            if (FA_BIAS_IS_NEG_INF(bias)) { valid = false; bias = (ACC_TYPE)(0.0f); }
        }
        ACC_TYPE p = (ACC_TYPE)(0.0f);
        if (valid) {
            if (logit_softcap > 0.0f) {
                score = logit_softcap * tanh(score / logit_softcap);
            }
            score += bias;
            p = exp(score - m_final);
        }
        l_i += p;
        #pragma unroll
        for (int i = 0; i < DV_VEC; i++) {
            o_acc[i] = mad(p, CONVERT_ACC4(v_ptr[i]), o_acc[i]);
        }
    }

    __local ACC_TYPE local_l[Q1_WG_SIZE];
    __local ACC_TYPE4 local_o_comp[Q1_WG_SIZE];
    local_l[tid] = l_i;
    barrier(CLK_LOCAL_MEM_FENCE);
    #pragma unroll
    for (int s = Q1_WG_SIZE / 2; s > 0; s >>= 1) {
        if (tid < s) local_l[tid] += local_l[tid + s];
        barrier(CLK_LOCAL_MEM_FENCE);
    }

    const ulong o_row_offset = batch_idx * o_nb3 + head_idx * o_nb1;
    global DATA_TYPE4 *o_row = (global DATA_TYPE4 *)(o_base + o_row_offset);
    ACC_TYPE l_final = local_l[0];

    if (sinks_ptr != NULL) {
        l_final += exp(sinks_ptr[head_idx] - m_final);
    }

    if (l_final > 0.0f) {
        const ACC_TYPE l_inv = 1.0f / l_final;
        for (int i = 0; i < DV_VEC; i++) {
            local_o_comp[tid] = o_acc[i];
            barrier(CLK_LOCAL_MEM_FENCE);
            #pragma unroll
            for (int s = Q1_WG_SIZE / 2; s > 0; s >>= 1) {
                if (tid < s) local_o_comp[tid] += local_o_comp[tid + s];
                barrier(CLK_LOCAL_MEM_FENCE);
            }
            if (tid == 0) {
                o_row[i] = CONVERT_DATA4(local_o_comp[0] * l_inv);
            }
        }
    } else if (tid == 0) {
        #pragma unroll
        for (int i = 0; i < DV_VEC; ++i) o_row[i] = (DATA_TYPE4)(0.0f);
    }
}
