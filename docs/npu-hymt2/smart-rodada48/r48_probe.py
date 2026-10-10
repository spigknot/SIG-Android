#!/usr/bin/env python3
# r48_probe.py — R48 §2/§3: A ROTA PRIVADA + O CONSUMIDOR FAIL-CLOSED!
# (1) mmtest:act = UMA op EXATA Q6_K K6144/N2048/B1 + gate count==1!
# (2) o consumer: capture_rc CONSUMIDO; dir por RUN_ID; temp+rename;
#     validação completa (type/K/N/B/version/rows/off/cap!); status!=2 no
#     CAPTURE_REQUIRED => fail consumido (nada de rep() solto!).
p = "C:/npu-probe/app/src/main/cpp/probe.cpp"
t = open(p, encoding="utf-8", errors="replace").read()

# --- (1) a rota ACT: inserir ANTES do "for (auto & cs : casos_min) {" ---
old1 = """            for (auto & cs : casos_min) {
                for (auto & ty : tipos) {
                    if (sub == "ctrl" && !ty.ctrl) continue;
                    if (sub == "q4k" && ty.ty != GGML_TYPE_Q4_K) continue;
                    if (sub == "q6k" && ty.ty != GGML_TYPE_Q6_K) continue;
                    roda_caso(cs.K, cs.N, cs.B, ty, true);   // R17: trace ligado (sup_h visivel!)
                }
            }"""
assert t.count(old1) == 1, "rota anchor!"
t = t.replace(old1, """            // R48 §2: A ROTA PRIVADA (UMA op EXATA — Q6_K K6144/N2048/B1!) com o
            // gate count==1: a captura REQUERIDA (CAPTURE_REQUIRED_ON!) so' nesta rota!
            if (sub == "act") {
                TY ty48 = { GGML_TYPE_Q6_K, "q6_K", false };
                static std::atomic<int> act_c48{0};
                const int c48 = ++act_c48;
                r48_capture_required = true;               // (o consumer exige CAPTURED!)
                if (c48 != 1) { rep("R48 ACT_ROTA_FAIL count=%d (!=1!) — recusa consumida!", c48); fail++; }
                else {
                    rep("R48 ACT_ROTA: a op EXATA (Q6_K K=6144 N=2048 B=1!) — count=1!");
                    roda_caso(6144, 2048, 1, ty48, true);
                }
                rep("MMTOTAL pass=%d fail=%d skip=%d compute_calls=%ld (rota ACT!)", pass, fail, skip, compute_calls);
                log_flush();
                if (bcpu) ggml_backend_free(bcpu);
                ggml_backend_free(bhtp);
                continue;
            }
            for (auto & cs : casos_min) {
                for (auto & ty : tipos) {
                    if (sub == "ctrl" && !ty.ctrl) continue;
                    if (sub == "q4k" && ty.ty != GGML_TYPE_Q4_K) continue;
                    if (sub == "q6k" && ty.ty != GGML_TYPE_Q6_K) continue;
                    roda_caso(cs.K, cs.N, cs.B, ty, true);   // R17: trace ligado (sup_h visivel!)
                }
            }""", 1)

# --- (1b) a flag r48_capture_required (declarar antes do lambda!) ---
old2 = """            auto trace2_any = [](bool x) { return x; };
            int pass = 0, fail = 0, skip = 0;"""
assert t.count(old2) == 1, "flag anchor!"
t = t.replace(old2, """            auto trace2_any = [](bool x) { return x; };
            int pass = 0, fail = 0, skip = 0;
            bool r48_capture_required = false;   // R48: ligada so' na rota ACT!""", 1)

# --- (2) O CONSUMIDOR FAIL-CLOSED (substitui o bloco R47 inteiro!) ---
old3 = """                // R47 (§4!): O CONSUMIDOR REAL — a captura da ACT via registry (test-only!
                // SO' depois do compute+readback deu certo — a resposta do batch ja' veio!)
                {
                    struct r47_st {
                        uint32_t magic, version; int32_t status, type, K, N, B;
                        uint32_t epoch, rows_done, payload_off, reserved;
                        uint64_t ptr, capacity, row_size;
                    } st47;
                    memset(&st47, 0, sizeof(st47));
                    int g47_ret = -1;
                    ggml_backend_reg_t reg47 = ggml_backend_dev_backend_reg(dev_h);
                    if (reg47) {
                        void * fn47 = ggml_backend_reg_get_proc_address(reg47, "ggml_hexagon_get_act_dump");
                        if (fn47) g47_ret = ((int (*)(void *, size_t)) fn47)(&st47, sizeof(st47));
                    }
                    rep("R47 ACT_DUMP: ret=%d status=%d magic=%s rows=%u/%d row=%zu cap=%llu",
                        g47_ret, g47_ret == 0 ? st47.status : -999,
                        (st47.magic == 0x52343644u) ? "OK" : "BAD(a API ausente?)",
                        st47.rows_done, st47.B, (size_t) st47.row_size, (unsigned long long) st47.capacity);
                    if (g47_ret == 0 && st47.status == 2 && st47.magic == 0x52343644u &&
                        st47.B == 1 && st47.rows_done == 1u && st47.row_size > 0 &&
                        st47.payload_off == 64 && 64 + st47.row_size <= st47.capacity && st47.ptr) {
                        mkdir("/data/data/br.gov.sp.pcsp.npuprobe/files/r47", 0770);
                        const char * p47 = "/data/data/br.gov.sp.pcsp.npuprobe/files/r47/act_payload.bin";
                        FILE * f47 = fopen(p47, "wb");
                        if (f47) {
                            const size_t wr47 = fwrite((const void *) ((uintptr_t) st47.ptr + st47.payload_off), 1, (size_t) st47.row_size, f47);
                            const int ff47 = fflush(f47);
                            const int fc47 = fclose(f47);
                            if (wr47 == (size_t) st47.row_size && ff47 == 0 && fc47 == 0)
                                rep("R47 EXPORT ok: %zu bytes -> %s", wr47, p47);
                            else rep("R47 EXPORT_FAIL (wr=%zu/%zu ff=%d fc=%d!)", wr47, (size_t) st47.row_size, ff47, fc47);
                        } else rep("R47 EXPORT_FAIL (fopen falhou!)");
                    } else if (g47_ret == 0 && st47.status == 2) {
                        rep("R47 EXPORT_RECUSADO (o consumidor nao aceitou os metadados!)");
                    }
                }"""
assert t.count(old3) == 1, "consumer anchor!"
t = t.replace(old3, """                // R48 §3: O CONSUMIDOR FAIL-CLOSED (capture_rc CONSUMIDO! dir por RUN_ID!
                // temp+rename! validação completa!) — SO' depois do compute+readback!
                {
                    struct r48_st {
                        uint32_t magic, version; int32_t status, type, K, N, B;
                        uint32_t epoch, rows_done, payload_off, reserved;
                        uint64_t ptr, capacity, row_size;
                    } st48;
                    memset(&st48, 0, sizeof(st48));
                    int g48_ret = -1;
                    ggml_backend_reg_t reg48 = ggml_backend_dev_backend_reg(dev_h);
                    if (reg48) {
                        void * fn48 = ggml_backend_reg_get_proc_address(reg48, "ggml_hexagon_get_act_dump");
                        if (fn48) g48_ret = ((int (*)(void *, size_t)) fn48)(&st48, sizeof(st48));
                    }
                    const bool val48 = (g48_ret == 0 && st48.status == 2 && st48.magic == 0x52343644u &&
                                        st48.version == 1u && st48.type == (int) GGML_TYPE_Q6_K &&
                                        st48.K == 6144 && st48.N == 2048 && st48.B == 1 &&
                                        st48.rows_done == 1u && st48.row_size > 0 &&
                                        st48.payload_off == 64 &&
                                        st48.row_size <= st48.capacity &&
                                        64 + st48.row_size <= st48.capacity &&
                                        st48.capacity <= (1u << 20) && st48.ptr != 0);
                    rep("R48 ACT_DUMP: ret=%d status=%d magic=%s ver=%u type=%d K=%d N=%d B=%d rows=%u row=%zu cap=%llu valido=%d req=%d",
                        g48_ret, g48_ret == 0 ? st48.status : -999,
                        (st48.magic == 0x52343644u) ? "OK" : "BAD",
                        st48.version, st48.type, st48.K, st48.N, st48.B, st48.rows_done,
                        (size_t) st48.row_size, (unsigned long long) st48.capacity,
                        (int) val48, (int) r48_capture_required);
                    int cap_rc48 = 0;
                    if (val48) {
                        char dir48[300], tmp48[340], fin48[340];
                        snprintf(dir48, sizeof(dir48), "/data/data/br.gov.sp.pcsp.npuprobe/files/r47/%s", attempt39);
                        if (mkdir("/data/data/br.gov.sp.pcsp.npuprobe/files/r47", 0770) != 0 && errno != EEXIST) { rep("R48 EXPORT_FAIL (mkdir r47: %s!)", strerror(errno)); cap_rc48 = -3; }
                        else if (mkdir(dir48, 0770) != 0 && errno != EEXIST) { rep("R48 EXPORT_FAIL (mkdir %s: %s!)", dir48, strerror(errno)); cap_rc48 = -3; }
                        else {
                            snprintf(tmp48, sizeof(tmp48), "%s/act_payload.bin.tmp_%d_%ld", dir48, (int) getpid(), (long) ++g_attempt_seq);
                            snprintf(fin48, sizeof(fin48), "%s/act_payload.bin", dir48);
                            FILE * f48 = fopen(tmp48, "wb");
                            if (!f48) { rep("R48 EXPORT_FAIL (fopen!)"); cap_rc48 = -4; }
                            else {
                                const size_t wr48 = fwrite((const void *) ((uintptr_t) st48.ptr + st48.payload_off), 1, (size_t) st48.row_size, f48);
                                const int ff48 = fflush(f48);
                                const int fc48 = fclose(f48);
                                if (wr48 == (size_t) st48.row_size && ff48 == 0 && fc48 == 0) {
                                    if (rename(tmp48, fin48) == 0) rep("R48 EXPORT ok: %zu bytes -> %s", wr48, fin48);
                                    else { rep("R48 EXPORT_FAIL (rename: %s!)", strerror(errno)); cap_rc48 = -5; }
                                } else { rep("R48 EXPORT_FAIL (wr=%zu/%zu ff=%d fc=%d!)", wr48, (size_t) st48.row_size, ff48, fc48); cap_rc48 = -6; }
                            }
                        }
                    } else {
                        cap_rc48 = (g48_ret != 0) ? -1 : -2;
                    }
                    if (r48_capture_required && cap_rc48 != 0) {
                        fail++;   // CONSUMIDO: a captura requerida falhou => fail do caso!
                        rep("R48 CAPTURE_REQUIRED_FAIL (cap_rc=%d — consumido no fail!)", cap_rc48);
                    } else if (r48_capture_required) {
                        rep("R48 CAPTURE_REQUIRED_OK (a captura requerida foi validada e exportada!)");
                    }
                }""", 1)
open(p, "w", encoding="utf-8", newline="\n").write(t)
print("probe R48 ok (rota ACT + consumer fail-closed!)")
print("chaves:", t.count("{"), t.count("}"))
