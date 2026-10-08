// R10: planejador de chunks do prefill — helper PURO (mesmo para pure/Smart).
// Regras: lotes de ate nb tokens; contiguos; o ULTIMO lote termina em nt-1
// (logits do ultimo token global no lote final); nt<=nb => 1 lote; nt<=0 => vazio.
#pragma once
#include <vector>
#include <utility>

inline std::vector<std::pair<int,int>> chunk_plan(int nt, int nb) {
    std::vector<std::pair<int,int>> out;
    if (nt <= 0 || nb <= 0) return out;
    for (int off = 0; off < nt; off += nb) {
        int n = (nt - off < nb) ? (nt - off) : nb;
        out.push_back(std::make_pair(off, n));
    }
    return out;
}
