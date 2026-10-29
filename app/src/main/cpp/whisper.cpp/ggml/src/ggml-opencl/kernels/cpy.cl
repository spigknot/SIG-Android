#pragma OPENCL EXTENSION cl_khr_fp16 : enable

// R17/F2 — CONVERSÃO f32 -> binary16 COM RNE EXPLÍCITO.
// `dst[i] = src[i]` (float -> half implícito) depende do driver: no Adreno 740
// ele TRUNCA (round toward zero). Medido com cast_probe: 2166/4096 casos
// divergem do IEEE RNE, 2010 deles 1 ULP abaixo. Aqui o padrão de 16 bits é
// montado em inteiro (round-to-nearest-even, subnormais preservados) e
// reinterpretado com as_half — sem aritmética em half, então o driver não tem
// onde aplicar outra regra.
ushort sig_f32_to_f16_bits_rne(float f) {
    uint x    = as_uint(f);
    uint sign = (x >> 16) & 0x8000u;
    uint absx = x & 0x7fffffffu;

    if (absx >= 0x7f800000u) {
        return (ushort)(sign | ((absx > 0x7f800000u) ? 0x7e00u : 0x7c00u));
    }

    int  e = (int)((absx >> 23) & 0xffu) - 127;
    uint m = absx & 0x7fffffu;

    if (e > 15)  return (ushort)(sign | 0x7c00u);          // overflow -> Inf

    if (e >= -14) {                                        // normal
        uint he   = (uint)(e + 15) << 10;
        uint mant = m >> 13;
        uint rem  = m & 0x1fffu;
        if (rem > 0x1000u || (rem == 0x1000u && (mant & 1u) != 0u)) mant += 1u;
        return (ushort)(sign | (he + mant));               // carry entra no expoente
    }

    if (e < -25) return (ushort)sign;                      // abaixo de half-ulp -> +-0

    int  shift = -1 - e;                                   // 14 .. 24
    uint full  = m | 0x800000u;
    uint mant  = full >> (uint)shift;
    uint rem   = full & ((1u << (uint)shift) - 1u);
    uint mid   = 1u << (uint)(shift - 1);
    if (rem > mid || (rem == mid && (mant & 1u) != 0u)) mant += 1u;
    return (ushort)(sign | mant);
}

//------------------------------------------------------------------------------
// cpy
//------------------------------------------------------------------------------

kernel void kernel_cpy_f16_f16(
        global half * src0,
        ulong offset0,
        global half * dst,
        ulong offsetd,
        int ne00,
        int ne01,
        int ne02,
        int ne03,
        ulong nb00,
        ulong nb01,
        ulong nb02,
        ulong nb03,
        int ne0,
        int ne1,
        int ne2,
        int ne3,
        ulong nb0,
        ulong nb1,
        ulong nb2,
        ulong nb3
) {
    src0 = (global half*)((global char*)src0 + offset0);
    dst = (global half*)((global char*)dst + offsetd);

    int i03 = get_group_id(2);
    int i02 = get_group_id(1);
    int i01 = get_group_id(0);

    int n = i03*ne02*ne01*ne00 + i02*ne01*ne00 + i01*ne00;

    int i3 = n / (ne2*ne1*ne0);
    int i2 = (n - i3*ne2*ne1*ne0) / (ne1*ne0);
    int i1 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0) / ne0;
    int i0 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0 - i1*ne0);

    global half * dst_data = (global half *) ((global char *) dst + i3*nb3 + i2*nb2 + i1*nb1 + i0*nb0);

    for (int i00 = get_local_id(0); i00 < ne00; i00 += get_local_size(0)) {
        global const half * src = (global half *)((global char *) src0 + i03*nb03 + i02*nb02 + i01*nb01 + i00*nb00);
        dst_data[i00] = src[0];
    }
}

kernel void kernel_cpy_f16_f32(
        global half * src0,
        ulong offset0,
        global float * dst,
        ulong offsetd,
        int ne00,
        int ne01,
        int ne02,
        int ne03,
        ulong nb00,
        ulong nb01,
        ulong nb02,
        ulong nb03,
        int ne0,
        int ne1,
        int ne2,
        int ne3,
        ulong nb0,
        ulong nb1,
        ulong nb2,
        ulong nb3
) {

    src0 = (global half*)((global char*)src0 + offset0);
    dst = (global float*)((global char*)dst + offsetd);

    int i03 = get_group_id(2);
    int i02 = get_group_id(1);
    int i01 = get_group_id(0);

    int n = i03*ne02*ne01*ne00 + i02*ne01*ne00 + i01*ne00;

    int i3 = n / (ne2*ne1*ne0);
    int i2 = (n - i3*ne2*ne1*ne0) / (ne1*ne0);
    int i1 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0) / ne0;
    int i0 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0 - i1*ne0);

    global float * dst_data = (global float *) ((global char *) dst + i3*nb3 + i2*nb2 + i1*nb1 + i0*nb0);

    for (int i00 = get_local_id(0); i00 < ne00; i00 += get_local_size(0)) {
        global half * src = (global half *)((global char *) src0 + i03*nb03 + i02*nb02 + i01*nb01 + i00*nb00);
        dst_data[i00] = src[0];
    }
}

kernel void kernel_cpy_f32_f16(
        global float * src0,
        ulong offset0,
        global half * dst,
        ulong offsetd,
        int ne00,
        int ne01,
        int ne02,
        int ne03,
        ulong nb00,
        ulong nb01,
        ulong nb02,
        ulong nb03,
        int ne0,
        int ne1,
        int ne2,
        int ne3,
        ulong nb0,
        ulong nb1,
        ulong nb2,
        ulong nb3
) {
    src0 = (global float*)((global char*)src0 + offset0);
    dst = (global half*)((global char*)dst + offsetd);

    int i03 = get_group_id(2);
    int i02 = get_group_id(1);
    int i01 = get_group_id(0);

    int n = i03*ne02*ne01*ne00 + i02*ne01*ne00 + i01*ne00;

    int i3 = n / (ne2*ne1*ne0);
    int i2 = (n - i3*ne2*ne1*ne0) / (ne1*ne0);
    int i1 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0) / ne0;
    int i0 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0 - i1*ne0);

    global half * dst_data = (global half *) ((global char *) dst + i3*nb3 + i2*nb2 + i1*nb1 + i0*nb0);

    for (int i00 = get_local_id(0); i00 < ne00; i00 += get_local_size(0)) {
        global const float * src = (global float *)((global char *) src0 + i03*nb03 + i02*nb02 + i01*nb01 + i00*nb00);

        // R17/F2 — RNE EXPLÍCITO (o cast implícito trunca no Adreno 740).
        dst_data[i00] = as_half(sig_f32_to_f16_bits_rne(src[0]));
    }
}

kernel void kernel_cpy_f32_f32(
        global float * src0,
        ulong offset0,
        global float * dst,
        ulong offsetd,
        int ne00,
        int ne01,
        int ne02,
        int ne03,
        ulong nb00,
        ulong nb01,
        ulong nb02,
        ulong nb03,
        int ne0,
        int ne1,
        int ne2,
        int ne3,
        ulong nb0,
        ulong nb1,
        ulong nb2,
        ulong nb3
) {
    src0 = (global float*)((global char*)src0 + offset0);
    dst = (global float*)((global char*)dst + offsetd);

    int i03 = get_group_id(2);
    int i02 = get_group_id(1);
    int i01 = get_group_id(0);

    int n = i03*ne02*ne01*ne00 + i02*ne01*ne00 + i01*ne00;

    int i3 = n / (ne2*ne1*ne0);
    int i2 = (n - i3*ne2*ne1*ne0) / (ne1*ne0);
    int i1 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0) / ne0;
    int i0 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0 - i1*ne0);

    global float * dst_data = (global float *) ((global char *) dst + i3*nb3 + i2*nb2 + i1*nb1 + i0*nb0);

    for (int i00 = get_local_id(0); i00 < ne00; i00 += get_local_size(0)) {
        global const float * src = (global float *)((global char *) src0 + i03*nb03 + i02*nb02 + i01*nb01 + i00*nb00);

        dst_data[i00] = src[0];
    }
}

kernel void kernel_cpy_i32_i32(
        global int * src0,
        ulong offset0,
        global int * dst,
        ulong offsetd,
        int ne00,
        int ne01,
        int ne02,
        int ne03,
        ulong nb00,
        ulong nb01,
        ulong nb02,
        ulong nb03,
        int ne0,
        int ne1,
        int ne2,
        int ne3,
        ulong nb0,
        ulong nb1,
        ulong nb2,
        ulong nb3
) {
    src0 = (global int*)((global char*)src0 + offset0);
    dst = (global int*)((global char*)dst + offsetd);

    int i03 = get_group_id(2);
    int i02 = get_group_id(1);
    int i01 = get_group_id(0);

    int n = i03*ne02*ne01*ne00 + i02*ne01*ne00 + i01*ne00;

    int i3 = n / (ne2*ne1*ne0);
    int i2 = (n - i3*ne2*ne1*ne0) / (ne1*ne0);
    int i1 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0) / ne0;
    int i0 = (n - i3*ne2*ne1*ne0 - i2*ne1*ne0 - i1*ne0);

    global int * dst_data = (global int *) ((global char *) dst + i3*nb3 + i2*nb2 + i1*nb1 + i0*nb0);

    for (int i00 = get_local_id(0); i00 < ne00; i00 += get_local_size(0)) {
        global const int * src = (global int *)((global char *) src0 + i03*nb03 + i02*nb02 + i01*nb01 + i00*nb00);

        dst_data[i00] = src[0];
    }
}
