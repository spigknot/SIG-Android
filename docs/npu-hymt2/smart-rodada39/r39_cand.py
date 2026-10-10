#!/usr/bin/env python3
# r39_cand.py: HOOK ACOPLADO (selecao explicita!) + Q6 + parse 0/1 + SNAPSHOT_FAIL!
BSN = chr(92) + "n"
Q = chr(34)
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    # (a) remover o hook ANTIGO (Q4 only, global sobrescrito!)
    old_hook = '''            if (getenv("GGML_HEXAGON_EXPORT_TILED")) {
                const size_t raw_sz = ggml_hexagon_tiled_row_size(tensor->type, hex_round_up(tensor->ne[0], 32))
                                      * hex_round_up(tensor->ne[1], 32) * tensor->ne[2] * tensor->ne[3];
                FILE * fraw = fopen("/data/data/br.gov.sp.pcsp.npuprobe/files/r36/tiled_raw.bin", "wb");
                if (fraw) { fwrite(tensor->data, 1, raw_sz, fraw); fclose(fraw);
                            fprintf(stderr, "R36_TILED_EXPORT ok sz=%zu\\n", raw_sz); }
                else fprintf(stderr, "R36_TILED_EXPORT FAIL (fopen!)\\n");
            }'''
    assert t.count(old_hook) == 1, path + " hook antigo: " + str(t.count(old_hook))
    # (b) o hook NOVO: uma funcao helper (test-only!) chamada nos DOIS cases (Q4+Q6!)
    new_helper = '''// R39: HOOK ACOPLADO (test-only!): le a SELECAO (attempt/K/N/B/type!) escrita pelo
// probe ANTES do upload; casa com ESTE tensor; escreve o RAW no dir UNICO do attempt;
// parse EXPLICITO da flag ('1' liga; '0'/unset = OFF!); NADA de sobrescrever global!
static void r39_tiled_hook(const ggml_tensor * tensor) {
    const char * flag = getenv("GGML_HEXAGON_EXPORT_TILED");
    if (!flag || flag[0] != '1' || flag[1] != 0) return;   // parse explicito (so' "1"!)
    FILE * fs = fopen("/data/data/br.gov.sp.pcsp.npuprobe/files/r39/sel.txt", "r");
    if (!fs) { fprintf(stderr, "R39_RAW FAIL (sel.txt ausente!)\\n"); return; }
    char attempt[64] = {0}, stype[16] = {0};
    int sK = 0, sN = 0, sB = 0;
    const int got = fscanf(fs, "%63s %d %d %d %15s", attempt, &sK, &sN, &sB, stype);
    fclose(fs);
    if (got != 5) { fprintf(stderr, "R39_RAW FAIL (sel invalida!)\\n"); return; }
    const char * myty = (tensor->type == GGML_TYPE_Q6_K) ? "q6_K" : "q4_K";
    if ((int) tensor->ne[0] != sK || (int) tensor->ne[1] != sN || strcmp(myty, stype) != 0) return;
    // casou: o RAW fisico (intervalo do helper REAL!) no dir do attempt!
    char dir[256], raw_path[320];
    snprintf(dir, sizeof(dir), "/data/data/br.gov.sp.pcsp.npuprobe/files/r39/%s", attempt);
    (void) mkdir(dir, 0770);
    const size_t raw_sz = ggml_hexagon_tiled_row_size(tensor->type, hex_round_up(tensor->ne[0], 32))
                          * hex_round_up(tensor->ne[1], 32) * tensor->ne[2] * tensor->ne[3];
    snprintf(raw_path, sizeof(raw_path), "%s/tiled_raw.bin", dir);
    FILE * fr = fopen(raw_path, "wb");
    if (!fr) { fprintf(stderr, "R39_RAW FAIL (fopen: %s!)\\n", raw_path); return; }
    const size_t wr = fwrite(tensor->data, 1, raw_sz, fr);
    const int cr = fflush(fr);
    const int cl = fclose(fr);
    if (wr != raw_sz || cr != 0 || cl != 0) fprintf(stderr, "R39_RAW FAIL (wr=%zu/%zu cr=%d cl=%d!)\\n", wr, raw_sz, cr, cl);
    else fprintf(stderr, "R39_RAW ok sz=%zu attempt=%s\\n", raw_sz, attempt);
}

'''
    # inserir o helper ANTES do set_tensor!
    anchor = "static void ggml_backend_hexagon_buffer_set_tensor(ggml_backend_buffer_t buffer,"
    assert t.count(anchor) == 1, path + " set_tensor anchor!"
    t = t.replace(anchor, new_helper + anchor, 1)
    # (c) Q4: remover o hook antigo + chamar o novo!
    t = t.replace(old_hook, "            r39_tiled_hook(tensor);   // R39: hook acoplado (Q4!)", 1)
    # (d) Q6: adicionar o hook (apos o repack_q6_K_tiled!)
    old6 = '''            repack_q6_K_tiled(tensor, data, offset, size);
            break;'''
    assert t.count(old6) == 1, path + " q6 repack!"
    t = t.replace(old6, '''            repack_q6_K_tiled(tensor, data, offset, size);
            r39_tiled_hook(tensor);   // R39: hook acoplado (Q6 — o entregavel!)
            break;''', 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R39 hook acoplado ok!")
