// ============================================================================
// TESTE NATIVO DO RESET DE PEDIDO — executável no HOST, sem modelo, sem aparelho.
//
// Exercita o helper REAL `hymt2_begin_fresh_request` (o mesmo que `generate`
// chama em `llama_jni.cpp`), compilado contra os headers REAIS da revisão
// vendored (`llama.h` + ggml). As duas funções do runtime usadas pelo helper
// (`llama_get_memory`, `llama_memory_clear`) são stubs com um modelo de
// memória que implementa o contrato documentado da revisão:
//   - posições por sequência; `seq_pos_max` = última posição;
//   - `clear(data=true)` esvazia posições (metadados + buffers);
//   - `llama_batch_get_one(pos=null)` parte de `seq_pos_max+1` (llama-batch.cpp).
// O que este teste PROVA: ordem get->clear, data=true, erro em mem null,
// e que após o reset a primeira posição volta a 0 (A->B->A, longo->curto,
// falha->novo pedido). O que ele NÃO prova: o comportamento interno do
// `llama_memory_clear` real (coberto pela evidência no alvo, com lib corrigida).
//
// Como rodar (MSYS2):
//   /c/msys64/mingw64/bin/g++.exe -std=c++17 -Wall -Wextra \
//     -ID:/Projetos/SIG/app/src/main/cpp/llama/include \
//     -ID:/Projetos/SIG/app/src/main/cpp/llama/ggml/include \
//     -ID:/Projetos/SIG/app/src/main/cpp/llama-jni \
//     hymt2_reset_test.cpp -o hymt2_reset_test.exe
//   ./hymt2_reset_test.exe   (rc=0 => passou)
// ============================================================================
#include "hymt2_request_reset.h"

#include <cstdio>
#include <string>
#include <vector>

// ---- modelo mínimo de memória: implementa o contrato documentado ----
struct FakeMem {
    std::vector<llama_pos> pos;   // posições da seq 0
    bool clear_data_flag = false;
    int n_clear = 0;
    int n_get = 0;
};

static FakeMem g_fake;
static bool g_null_mem = false;
static std::vector<std::string> g_ordem;

llama_memory_t llama_get_memory(const struct llama_context * /*ctx*/) {
    g_ordem.push_back("get");
    g_fake.n_get++;
    if (g_null_mem) return nullptr;
    return reinterpret_cast<llama_memory_t>(&g_fake);
}

void llama_memory_clear(llama_memory_t mem, bool data) {
    g_ordem.push_back(data ? "clear(data=true)" : "clear(data=false)");
    FakeMem * f = reinterpret_cast<FakeMem *>(mem);
    f->clear_data_flag = data;
    f->n_clear++;
    f->pos.clear();
}

// simula o prefill: escreve posições a partir de seq_pos_max+1
static void simula_prefill(FakeMem & f, int n_tokens) {
    llama_pos base = f.pos.empty() ? 0 : f.pos.back() + 1;
    for (int i = 0; i < n_tokens; i++) f.pos.push_back(base + i);
}
static llama_pos seq_pos_max(const FakeMem & f) {
    return f.pos.empty() ? -1 : f.pos.back();
}

static int g_pass = 0, g_fail = 0;
#define CHECA(nome, cond) do { \
    if (cond) { g_pass++; printf("  ok   %s\n", nome); } \
    else { g_fail++; printf("  FALHA %s\n", nome); } \
} while (0)

int main() {
    llama_context * ctx = reinterpret_cast<llama_context *>(0x1234);
    std::string err;

    printf("== T1: reset esvazia e primeira posicao volta a 0 (longo->curto) ==\n");
    g_fake = FakeMem(); g_ordem.clear();
    simula_prefill(g_fake, 4924);  // pedido longo anterior
    CHECA("seq_pos_max reflete o pedido anterior", seq_pos_max(g_fake) == 4923);
    CHECA("helper retorna true", hymt2_begin_fresh_request(ctx, &err));
    CHECA("get antes de clear", g_ordem.size() == 2 && g_ordem[0] == "get" &&
          g_ordem[1] == "clear(data=true)");
    CHECA("clear com data=true", g_fake.clear_data_flag);
    CHECA("seq_pos_max vazio apos reset", seq_pos_max(g_fake) == -1);
    simula_prefill(g_fake, 409);   // pedido curto: primeira posicao 0
    CHECA("primeira posicao do novo pedido e 0", g_fake.pos.front() == 0);
    CHECA("pedido novo tem so os proprios tokens", (int) g_fake.pos.size() == 409);

    printf("== T2: A->B->A sem load/release entre pedidos ==\n");
    g_fake = FakeMem();
    simula_prefill(g_fake, 100);   // A
    CHECA("reset entre A e B", hymt2_begin_fresh_request(ctx, &err));
    simula_prefill(g_fake, 50);    // B
    CHECA("B comeca em 0", g_fake.pos.front() == 0 && (int) g_fake.pos.size() == 50);
    CHECA("reset entre B e A", hymt2_begin_fresh_request(ctx, &err));
    simula_prefill(g_fake, 100);   // A de novo
    CHECA("A repetido comeca em 0 e tem 100", g_fake.pos.front() == 0 &&
          (int) g_fake.pos.size() == 100);

    printf("== T3: falha durante pedido + pedido novo ==\n");
    g_fake = FakeMem();
    simula_prefill(g_fake, 200);
    // falha simulada no meio (sem reset): estado sujo permanece...
    CHECA("estado sujo antes do reset", seq_pos_max(g_fake) == 199);
    // ...mas o proximo pedido limpa de novo (politica de erros do design)
    CHECA("reset do proximo pedido", hymt2_begin_fresh_request(ctx, &err));
    CHECA("sujo removido", seq_pos_max(g_fake) == -1);

    printf("== T4: memoria null => erro, sem efeito ==\n");
    g_null_mem = true; g_ordem.clear();
    err.clear();
    CHECA("retorna false", !hymt2_begin_fresh_request(ctx, &err));
    CHECA("erro preenchido", !err.empty());
    CHECA("nenhum clear apos falha", g_ordem.size() == 1 && g_ordem[0] == "get");
    g_null_mem = false;

    printf("\n===== %d passaram, %d falharam =====\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
