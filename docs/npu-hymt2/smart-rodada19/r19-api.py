import subprocess
K = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(K, "rb").read().replace(b"\r\n", b"\n").decode().splitlines()
print("=== (1) repack_buffer_type (sess->repack_buffer_type!) ===")
for i, ln in enumerate(t):
    if "repack_buffer_type" in ln:
        print(f"{i+1:5}|{ln.strip()[:140]}")
print()
print("=== (2) get_extra_bufts (a extensao!) ===")
for i, ln in enumerate(t):
    if "get_extra_bufts" in ln or "extra_buf" in ln:
        print(f"{i+1:5}|{ln.strip()[:140]}")
print()
print("=== (3) opt_hostbuf (o gate!) ===")
for i, ln in enumerate(t):
    if "hostbuf" in ln.lower():
        print(f"{i+1:5}|{ln.strip()[:130]}")
