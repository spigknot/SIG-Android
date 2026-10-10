#!/usr/bin/env python3
# r37_gguf_invent.py: inventario de SHAPES do GGUF produto (so metadados!)
# Le o header/tenant infos do GGUF (o c4bf!) — nao le 1GB de dados!
import struct, sys, collections

P = r"D:\Projetos\SIG\docs\vulkan-hymt2-fusion-round\logs-rodada13\hymt2_app.gguf"
f = open(P, "rb")
magic = f.read(4)
assert magic == b"GGUF", magic
ver, n_tensors, n_kv = struct.unpack("<IQQ", f.read(20))
print("gguf v%d, %d tensores, %d kv" % (ver, n_tensors, n_kv))

def rd_str():
    n = struct.unpack("<Q", f.read(8))[0]
    return f.read(n).decode("utf-8", "replace")

# kv (pular!)
T_U8,T_I8,T_U16,T_I16,T_U32,T_I32,T_F32,T_BOOL,T_STR,T_ARR,T_U64,T_I64,T_F64 = range(13)
def skip_val(t):
    if t == T_STR: rd_str()
    elif t == T_ARR:
        et = struct.unpack("<I", f.read(4))[0]
        en = struct.unpack("<Q", f.read(8))[0]
        for _ in range(en): skip_val(et)
    elif t in (T_U8,T_I8,T_BOOL): f.read(1)
    elif t in (T_U16,T_I16): f.read(2)
    elif t in (T_U32,T_I32,T_F32): f.read(4)
    elif t in (T_U64,T_I64,T_F64): f.read(8)
for _ in range(n_kv):
    k = rd_str()
    t = struct.unpack("<I", f.read(4))[0]
    skip_val(t)

# os tensores (nomes/tipos/dims!)
TIPOS = {0:"F32",1:"F16",2:"Q4_0",3:"Q4_1",6:"Q5_0",7:"Q5_1",8:"Q8_0",9:"Q8_1",
         12:"Q4_K",14:"Q6_K",10:"Q2_K",11:"Q3_K"}
tens = []
for i in range(n_tensors):
    nome = rd_str()
    nd = struct.unpack("<I", f.read(4))[0]
    dims = struct.unpack("<%dQ" % nd, f.read(8 * nd))
    ty = struct.unpack("<I", f.read(4))[0]
    off = struct.unpack("<Q", f.read(8))[0]
    tens.append((nome, TIPOS.get(ty, "T%d" % ty), dims))
# os MUL_MAT (2D!) por tipo!
mm = [(n, t, d) for (n, t, d) in tens if len(d) == 2]
bytype = collections.Counter(t for (_, t, _) in mm)
print("\nTensores 2D (MUL_MAT!) por tipo:", dict(bytype))
print("\nQ4_K (K!=256!):")
for (n, t, d) in mm:
    if t == "Q4_K":
        print("  %-50s K=%d N=%d" % (n[:50], d[0], d[1]))
print("\nQ6_K:")
for (n, t, d) in mm:
    if t == "Q6_K":
        print("  %-50s K=%d N=%d" % (n[:50], d[0], d[1]))
# os shapes UNICOS!
shapes = sorted(set((t, d[0], d[1]) for (_, t, d) in mm if t in ("Q4_K","Q6_K")))
print("\nShapes unicos K-quant (tipo,K,N):")
for s in shapes: print("  ", s)
print("\nR37_INVENT_DONE")
