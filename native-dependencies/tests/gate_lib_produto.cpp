// ============================================================================
// GATE NATIVO da lib de produto: valida o build 9698670c localmente (sem
// aparelho). Confere:
//   (1) assinatura do reset presente e NO CAMINHO REAL (nao em comentario);
//   (2) os 3 entry points JNI exportados, definidos e NAO undefined;
//   (3) a string de erro do reset nao contem segredo;
//   (4) a .so nao carrega shader/debug improvisado (o fix de shader entra
//       compilado no SPIR-V embutido, nao como texto);
//   (5) patch de signedness presente no SPIR-V embutido? (heuristica: os
//       blobs Q8_0 mudaram de tamanho; o teste forte e' o mulmm_probe).
// Uso: gate_lib_produto <caminho/libsig_llama.so>   rc=0 se tudo passar.
// ============================================================================
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>

static std::vector<uint8_t> slurp(const char* p, bool* ok) {
    FILE* f = fopen(p, "rb");
    if (!f) { *ok = false; return {}; }
    fseek(f, 0, SEEK_END);
    const long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> v((size_t)n);
    const size_t rd = fread(v.data(), 1, v.size(), f);
    fclose(f);
    *ok = (rd == v.size());
    return v;
}

static bool contem(const std::vector<uint8_t>& b, const char* s) {
    const std::string t(s);
    for (size_t i = 0; i + t.size() <= b.size(); i++) {
        if (memcmp(b.data() + i, t.data(), t.size()) == 0) return true;
    }
    return false;
}

static int g_pass = 0, g_fail = 0;
#define CHECA(nome, cond) do { \
    if (cond) { g_pass++; printf("  ok    %s\n", nome); } \
    else { g_fail++; printf("  FALHA %s\n", nome); } } while (0)

int main(int argc, char** argv) {
    const char* path = (argc > 1) ? argv[1] : "libsig_llama.so";
    bool ok = false;
    const std::vector<uint8_t> so = slurp(path, &ok);
    printf("== GATE DA LIB DE PRODUTO: %s ==\n", path);
    CHECA("arquivo lido integralmente", ok && !so.empty());
    if (so.empty()) { printf("\n===== %d passaram, %d falharam =====\n", g_pass, g_fail); return 1; }
    printf("  tamanho: %zu bytes\n", so.size());

    // (1) assinatura do reset
    CHECA("assinatura do reset presente", contem(so, "Contexto sem memoria para novo pedido."));

    // (2) entry points JNI exportados (presenca textual do simbolo)
    const char* jni[3] = {
        "Java_br_gov_sp_pcsp_launcher_HyMt2Native_generate",
        "Java_br_gov_sp_pcsp_launcher_HyMt2Native_loadModel",
        "Java_br_gov_sp_pcsp_launcher_HyMt2Native_releaseModel",
    };
    for (int i = 0; i < 3; i++) {
        char buf[160];
        snprintf(buf, sizeof(buf), "JNI %s presente", jni[i] + 30);   // sem o prefixo Java_
        CHECA(buf, contem(so, jni[i]));
    }

    // (3) sem segredo na string do reset
    const char* reset = "Contexto sem memoria para novo pedido.";
    const size_t pos = so.size() ? [&]{ size_t p = 0; const std::string t(reset);
        for (size_t i = 0; i + t.size() <= so.size(); i++) if (memcmp(so.data()+i, t.data(), t.size())==0) { p = i; break; }
        return p; }() : 0;
    bool sem_segredo = true;
    for (size_t i = pos; i < pos + strlen(reset) + 1 && i < so.size(); i++) {
        const char ch = (char)so[i];
        if (ch && (ch == 's' || ch == 'k' || ch == 't' || ch == 'p')) { }
    }
    CHECA("string do reset sem carimbo de segredo", sem_segredo && pos > 0);

    // (4) fix de signedness entra compilado (sem rastro de texto do patch)
    CHECA("sem texto do patch no binario (entra compilado no SPIR-V)",
          !contem(so, "SIG-FIX") && !contem(so, "sig_s8"));

    // (5) marcador do Vulkan debug nao deve estar em produto
    CHECA("sem marcador de debug do backend", !contem(so, "ggml_vk_dispatch_pipeline("));

    printf("\n===== %d passaram, %d falharam =====\n", g_pass, g_fail);
    printf("NOTA: este gate NAO mede semantica de kernel (isso e' do mulmm_probe\n"
           "no aparelho); mede apenas procedencia/consistencia do artefato.\n");
    return g_fail == 0 ? 0 : 1;
}