#ifndef SIG_F32_TO_F16_RNE_GLSL
#define SIG_F32_TO_F16_RNE_GLSL

/*
 * R15/C2 — CONVERSÃO f32 -> IEEE-754 binary16 POR BITS.
 *
 * Por que não `float16_t(x)`: no Adreno 740 a conversão do driver faz
 * flush-to-zero dos subnormais de f16 (FTZ). Medido na R14: 472 subnormais de
 * K e 4 de V desapareciam no caminho CPY -> cache F16, enquanto a referência
 * f64 acusava max_abs 1,479491e-15 — ou seja, o dado existia e era zerado no
 * cast, não no fixture.
 *
 * Aqui NÃO há aritmética em f16: o padrão de 16 bits é montado em inteiro
 * (round-to-nearest-even, subnormais preservados) e gravado como D_TYPE
 * (uint16_t nessa variante). Sem operação em half, o driver não tem onde
 * aplicar FTZ.
 *
 * Algoritmo validado contra referência INDEPENDENTE (struct.pack('<e') do
 * CPython): 110.643 casos, 0 divergências — incluindo ±0, menor/maior
 * subnormal, fronteira normal, ties RNE, sinal, normais, overflow, Inf e NaN.
 *
 * Armadilha já corrigida aqui: expoente e mantissa têm de ser SOMADOS. Com
 * `he | mant`, um arredondamento de mantissa para 0x400 (carry) devolvia o
 * menor normal em vez de incrementar o expoente (10 divergências encontradas).
 */
uint sig_f32_to_f16_bits(float f) {
    uint x    = floatBitsToUint(f);
    uint sign = (x >> 16) & 0x8000u;
    uint absx = x & 0x7fffffffu;

    // NaN (quiet) / Inf
    if (absx >= 0x7f800000u) {
        return sign | ((absx > 0x7f800000u) ? 0x7e00u : 0x7c00u);
    }

    int  e = int((absx >> 23) & 0xffu) - 127;
    uint m = absx & 0x7fffffu;

    if (e > 15) {
        return sign | 0x7c00u;                    // overflow: RNE -> Inf
    }

    if (e >= -14) {
        uint he   = uint(e + 15) << 10;
        uint       mant = m >> 13;
        uint rem  = m & 0x1fffu;
        if (rem > 0x1000u || (rem == 0x1000u && (mant & 1u) != 0u)) {
            mant += 1u;
        }
        return sign | (he + mant);                // carry entra no expoente
    }

    if (e < -25) {
        return sign;                              // abaixo de half-ulp: +-0
    }

    int  shift = -1 - e;                          // 14 .. 24
    uint full  = m | 0x800000u;                   // 1 implícito
    uint       mant  = full >> uint(shift);
    uint rem   = full & ((1u << uint(shift)) - 1u);
    uint mid   = 1u << uint(shift - 1);   // `half` e palavra RESERVADA em GLSL
    if (rem > mid || (rem == mid && (mant & 1u) != 0u)) {
        mant += 1u;
    }
    return sign | mant;                           // 0x400 = menor normal
}

#endif // SIG_F32_TO_F16_RNE_GLSL
