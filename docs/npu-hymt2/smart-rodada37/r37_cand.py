#!/usr/bin/env python3
# r37_cand.py: consulta diagnostica PRIVADA (test-only!) do opt_opstage RETIDO!
BSN = chr(92) + "n"
Q = chr(34)
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    # (a) a funcao do getter (perto do opt_opstage!)
    anchor = "// Enable all stages by default\nstatic int opt_opstage  = HTP_OPSTAGE_QUEUE | HTP_OPSTAGE_COMPUTE;"
    assert t.count(anchor) == 1, "anchor opstage: %d" % t.count(anchor)
    t = t.replace(anchor, anchor + """

// R37: consulta diagnostica PRIVADA/test-only: o opt_opstage EFETIVAMENTE RETIDO
// pela lib inicializada (nao o getenv atual!) — exposta via get_proc_address!
static int ggml_hexagon_get_opstage_effective(void) {
    return opt_opstage;
}""", 1)
    # (b) o nome no get_proc_address!
    old2 = '''    if (strcmp(name, "ggml_backend_dev_get_extra_bufts") == 0 && opt_hostbuf) {'''
    assert t.count(old2) == 1, "proc_address anchor!"
    t = t.replace(old2, '''    if (strcmp(name, "ggml_hexagon_get_opstage_effective") == 0) {
        // R37: consulta test-only (o valor RETIDO — para o preflight do caller!)
        return (void *) ggml_hexagon_get_opstage_effective;
    }
    if (strcmp(name, "ggml_backend_dev_get_extra_bufts") == 0 && opt_hostbuf) {''', 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R37 getter ok!")
