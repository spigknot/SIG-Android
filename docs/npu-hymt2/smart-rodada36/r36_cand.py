#!/usr/bin/env python3
# r36_cand.py: (a) log do OPSTAGE efetivo no init; (b) hook RAW do tiled pos-repack!
BSN = chr(92) + "n"
Q = chr(34)
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    # (a) o log do OPSTAGE efetivo (logo apos a atribuicao do opt_opstage!)
    old = "    opt_opstage   = str_opstage  ? strtoul(str_opstage, NULL, 0)          : opt_opstage;"
    assert t.count(old) == 1, path + " opstage count=" + str(t.count(old))
    new = ("    opt_opstage   = str_opstage  ? strtoul(str_opstage, NULL, 0)          : opt_opstage;\n"
           "    // R36: OPSTAGE efetivo pos-init (o valor RETIDO — o gate diz que nao basta getenv!)\n"
           "    GGML_LOG_DEBUG(" + Q + "[hex] OPSTAGE_AT_INIT efetivo=%d (env=%s!)" + BSN + Q + ", opt_opstage, str_opstage ? str_opstage : " + Q + "(default!)" + Q + ");")
    t = t.replace(old, new, 1)
    # (b) o hook RAW do tiled (no set_tensor, ANTES do repack — o data de ENTRADA e' canonico;
    #     DEPOIS do repack o t->data tem o tiled! — capturar POS-repack!)
    old2 = '''            repack_q4_K_tiled(tensor, data, offset, size);
            break;'''
    assert t.count(old2) == 1, path + " repack call count=" + str(t.count(old2))
    new2 = '''            repack_q4_K_tiled(tensor, data, offset, size);
            // R36: hook RAW (test-only, desligado por padrao!): copia o tiled FISICO
            // (intervalo do helper real!) apos o repack do candidato!
            if (getenv("GGML_HEXAGON_EXPORT_TILED")) {
                const size_t raw_sz = ggml_hexagon_tiled_row_size(tensor->type, hex_round_up(tensor->ne[0], 32))
                                      * hex_round_up(tensor->ne[1], 32) * tensor->ne[2] * tensor->ne[3];
                FILE * fraw = fopen("/data/data/br.gov.sp.pcsp.npuprobe/files/r36/tiled_raw.bin", "wb");
                if (fraw) { fwrite(tensor->data, 1, raw_sz, fraw); fclose(fraw);
                            fprintf(stderr, "R36_TILED_EXPORT ok sz=%zu\\n", raw_sz); }
                else fprintf(stderr, "R36_TILED_EXPORT FAIL (fopen!)\\n");
            }
            break;'''
    t = t.replace(old2, new2, 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R36 patches ok (opstage-log + tiled hook!)")
