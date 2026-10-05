// Isolamento de pedidos Hy-MT2: helper de reset usado pelo `generate`.
//
// Mesmo código no produto (`llama_jni.cpp`) e no teste de host
// (`hymt2_reset_test.cpp`): sem duplicação. Só depende da API real do
// llama (`llama.h`); sem Android/JNI para compilar no host.
// O chamador (generate) já está sob `g_mutex`.
#pragma once

#include <string>

#include "llama.h"

/** Prepara o contexto para um pedido independente (contrato testável).
 *
 * Limpa a memória KV/posição do pedido anterior. Devolve o erro em
 * `error_out` (sem tocar em log) para ser testável no host; o `generate`
 * converte em `set_error`. Modelo/pesos permanecem carregados. Retorna
 * false sem efeito parcial além da leitura (nenhum prefill/decode aqui).
 */
static inline bool hymt2_begin_fresh_request(llama_context * ctx, std::string * error_out) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem == nullptr) {
        if (error_out != nullptr) {
            *error_out = "Contexto sem memoria para novo pedido.";
        }
        return false;
    }
    llama_memory_clear(mem, true);
    return true;
}
