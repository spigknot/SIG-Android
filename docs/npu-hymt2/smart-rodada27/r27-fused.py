BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    old = "    const size_t src1_row_size = (wtype == GGML_TYPE_Q4_1) ? htp_mm_q8_1_tiled_row_size(ne10) : htp_mm_q8_0_tiled_row_size(ne10);"
    new = "    const size_t src1_row_size = (wtype == GGML_TYPE_Q4_1 || wtype == GGML_TYPE_Q4_K) ? htp_mm_q8_1_tiled_row_size(ne10) : htp_mm_q8_0_tiled_row_size(ne10);  // R27 FIX"
    n = t.count(old)
    t = t.replace(old, new)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "fused-fix =", n)
