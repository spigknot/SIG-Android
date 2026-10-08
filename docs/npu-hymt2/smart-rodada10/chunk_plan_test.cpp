// R10: teste host do chunk_plan (fixture de borda do chunking).
// Compilar: g++ -std=c++17 chunk_plan_test.cpp -o chunk_plan_test && ./chunk_plan_test
#include "chunk_plan.h"
#include <cstdio>
#include <cstdlib>

static int falhas = 0;
static void check(bool c, const char * msg) {
    if (!c) { printf("FAIL: %s\n", msg); falhas++; } else { printf("ok: %s\n", msg); }
}

int main() {
    auto c0   = chunk_plan(0, 128);      check(c0.empty(), "nt=0 -> vazio (nunca decode)");
    auto cN   = chunk_plan(-5, 128);     check(cN.empty(), "nt<0 -> vazio");
    auto c1   = chunk_plan(1, 128);      check(c1.size()==1 && c1[0].first==0 && c1[0].second==1, "nt=1 -> 1 lote {0,1}");
    auto c127 = chunk_plan(127, 128);    check(c127.size()==1 && c127[0].second==127, "nt=127 -> 1 lote");
    auto c128 = chunk_plan(128, 128);    check(c128.size()==1 && c128[0].second==128, "nt=128 -> 1 lote cheio");
    auto c129 = chunk_plan(129, 128);    check(c129.size()==2 && c129[0].second==128 && c129[1].first==128 && c129[1].second==1, "nt=129 -> {128,1}");
    auto c256 = chunk_plan(256, 128);    check(c256.size()==2 && c256[1].second==128, "nt=256 -> 2 lotes cheios");
    auto c300 = chunk_plan(300, 128);    check(c300.size()==3 && c300[2].first==256 && c300[2].second==44, "nt=300 -> {128,128,44}");
    // invariantes gerais
    int casos[] = {1, 63, 128, 129, 255, 256, 257, 512, 990};
    for (int nt : casos) {
        auto c = chunk_plan(nt, 128);
        int soma = 0, off_esperado = 0; bool contiguo = true, limite = true; int ult = -1;
        for (auto & p : c) {
            if (p.first != off_esperado) contiguo = false;
            if (p.second > 128 || p.second <= 0) limite = false;
            soma += p.second; off_esperado += p.second; ult = p.first + p.second - 1;
        }
        char m[160];
        snprintf(m, sizeof(m), "nt=%d: soma==nt", nt); check(soma == nt, m);
        snprintf(m, sizeof(m), "nt=%d: contiguo", nt); check(contiguo, m);
        snprintf(m, sizeof(m), "nt=%d: limite<=128", nt); check(limite, m);
        snprintf(m, sizeof(m), "nt=%d: ultimo termina em nt-1", nt); check(ult == nt - 1, m);
    }
    printf(falhas ? "\nRESULTADO: %d FALHAS\n" : "\nRESULTADO: TODOS PASS\n", falhas);
    return falhas ? 1 : 0;
}
