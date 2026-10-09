// GERADO — trecho REAL do probe (L940..951!), adaptado nos limites (declarado!): rep->printf, ggml_free->fake_free!
                    // R22 ALLOC_FAIL fail-closed (§3): REPACK selecionado QUE FALHA
                    // => ALLOC_FAIL explicito (NUNCA fallback silencioso ao default!)
                    ggml_backend_buffer_t bw = nullptr;
                    size_t req_alloc = 0, got_alloc = 0;
                    if (is_htp && buft_repack) {
                        req_alloc = ggml_backend_alloc_ctx_tensors_from_buft_size(cw, buft_repack);
                        bw = ggml_backend_alloc_ctx_tensors_from_buft(cw, buft_repack);
                        if (!bw) {
                            rep("MM[%s] ALLOC_FAIL (REPACK selecionado falhou! req=%zu)", ty.nm, req_alloc);
                            rc = -996;
                            ggml_free(cw); ggml_free(ca);
                            return false;
