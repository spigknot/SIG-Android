// R19/D1 — PEDIDO de audio_ctx TRANSACIONAL por dono.
//
// Defeito da R18: o pedido vivia em DOIS atômicos separados (`g_audio_ctx_owner`
// e `g_audio_ctx_override`). Entre gravar o dono e gravar o valor, outro
// controlador podia publicar e o consumo casava dono de B com valor de A:
//
//   A publica owner=101
//   B publica owner=202
//   B publica value=512
//   A retoma e publica value=268
//   B vê owner=202 e consome 268      <-- valor de A com dono de B
//
// E um único slot fazia B apagar o pedido de A ("CLEAR sem dono").
//
// Este header resolve os dois: cada pedido é UMA palavra de 64 bits publicada
// atomicamente (nunca há estado intermediário), e há uma TABELA de slots, de
// modo que donos distintos conservam o próprio pedido.
//
// Layout da palavra (0 = slot vazio):
//   bits 63..32 : owner (geração; > 0)
//   bits 31..0  : valor de audio_ctx (positivo)
//
// Não depende de Vulkan nem de Android: o teste roda no host e no aparelho.
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

#define SIG_AC_SLOTS 4
#define SIG_AC_OWNER_MAX 0xFFFFFFFFull
#define SIG_AC_ATTEMPTS 8

// R22/C1 — AUTORIDADE SIMPLES. O laço lock-free tinha DUAS pausas internas
// (antes do CAS e antes do load que testa slot vazio) e cada remendo cobria só
// uma. Um mutex pequeno, FORA do caminho de compute, lineariza publicação,
// consumo e cancelamento: não existe mais intercalação parcial.
static std::mutex g_ac_mutex;

// R20/C1 — owner fora de [1, 0xFFFFFFFF] e RECUSADO, nunca truncado
// (a R19 aliasava 4294967419 -> 123).
static inline bool sig_ac_owner_valido(int64_t owner) {
    return owner > 0 && static_cast<uint64_t>(owner) <= SIG_AC_OWNER_MAX;
}

static inline uint64_t sig_ac_pack(int64_t owner, int value) {
    if (!sig_ac_owner_valido(owner) || value <= 0) {
        return 0;
    }
    return (static_cast<uint64_t>(owner) << 32) |
           static_cast<uint32_t>(value);
}

static inline int64_t sig_ac_owner_of(uint64_t w) {
    return static_cast<int64_t>(w >> 32);
}

static inline int sig_ac_value_of(uint64_t w) {
    return static_cast<int>(w & 0xFFFFFFFFu);
}

// Publica o pedido do dono. Se já existe slot do mesmo dono, substitui (novo
// valor vence). Se não, ocupa um slot vazio. Devolve o índice do slot, ou -1 se
// a tabela estiver cheia (pedido NÃO publicado — nunca sobrescreve outro dono).
static inline int sig_ac_publish(std::atomic<uint64_t> * slots, int n, int64_t owner, int value) {
    const uint64_t novo = sig_ac_pack(owner, value);
    if (novo == 0) {
        return -1;
    }
    // R22/C1 — publicacao LINEARIZADA: a operacao inteira sob a autoridade.
    std::lock_guard<std::mutex> lk(g_ac_mutex);
    for (int i = 0; i < n; ++i) {
        if (sig_ac_owner_of(slots[i].load(std::memory_order_relaxed)) == owner) {
            slots[i].store(novo, std::memory_order_relaxed);
            return i;
        }
    }
    for (int i = 0; i < n; ++i) {
        if (slots[i].load(std::memory_order_relaxed) == 0) {
            slots[i].store(novo, std::memory_order_relaxed);
            return i;
        }
    }
    return -1;
}

// Consome APENAS o pedido do dono. Devolve true e o valor quando o slot é dele;
// o slot é limpo de forma atômica, de modo que dois consumidores não recebem o
// mesmo pedido. Pedido de outro dono permanece intacto.
static inline bool sig_ac_take(std::atomic<uint64_t> * slots, int n, int64_t owner, int * out_value) {
    if (!sig_ac_owner_valido(owner)) {
        return false;
    }
    std::lock_guard<std::mutex> lk(g_ac_mutex);
    for (int i = 0; i < n; ++i) {
        const uint64_t w = slots[i].load(std::memory_order_relaxed);
        if (sig_ac_owner_of(w) != owner) {
            continue;
        }
        slots[i].store(0, std::memory_order_relaxed);
        if (out_value != nullptr) {
            *out_value = sig_ac_value_of(w);
        }
        return true;
    }
    return false;
}

// Cancela o pedido do dono (job cancelado antes de consumir). Só o dono.
static inline bool sig_ac_cancel(std::atomic<uint64_t> * slots, int n, int64_t owner) {
    return sig_ac_take(slots, n, owner, nullptr);
}

static inline int sig_ac_pending(std::atomic<uint64_t> * slots, int n) {
    std::lock_guard<std::mutex> lk(g_ac_mutex);
    int c = 0;
    for (int i = 0; i < n; ++i) {
        if (slots[i].load(std::memory_order_acquire) != 0) {
            ++c;
        }
    }
    return c;
}

// Diagnóstico: dono do primeiro slot ocupado (-1 se vazio).
static inline int64_t sig_ac_first_owner(std::atomic<uint64_t> * slots, int n) {
    std::lock_guard<std::mutex> lk(g_ac_mutex);
    for (int i = 0; i < n; ++i) {
        const uint64_t w = slots[i].load(std::memory_order_acquire);
        if (w != 0) {
            return sig_ac_owner_of(w);
        }
    }
    return -1;
}
