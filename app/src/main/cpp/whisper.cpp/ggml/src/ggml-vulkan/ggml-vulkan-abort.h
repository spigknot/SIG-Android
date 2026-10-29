// R13/D1 passo 2 — observação de cancelamento no backend Vulkan (seam puro).
//
// Este header NÃO depende de Vulkan nem de ggml de propósito: é o contrato de
// decisão que `ggml_backend_vk_graph_compute` usa em cada fronteira segura, e
// pode ser exercitado no host sem device (tests/seam_test.cpp).
//
// Contrato (regras do D1 que este seam codifica):
//   1. o pedido é lido de fora do mutex do compute — `cb` é uma leitura atômica
//      do lado de quem pede o cancel; o backend nunca bloqueia para perguntar;
//   2. observado o pedido, `observed` fica verdadeiro até `reset()`: nenhuma
//      unidade NOVA é submetida depois disso (nem o lote já gravado);
//   3. depois de observado, o callback não é mais chamado (uma decisão por
//      grafo), então o custo no caminho feliz é 1 chamada por unidade;
//   4. abortar tem prioridade sobre qualquer submit devido.
//
// O que este seam NÃO decide: como drenar trabalho já submetido. Isso é feito
// em ggml-vulkan.cpp com a fence do próprio dono do contexto (nunca com uma
// fence nova por nó, nunca com kill/detach).
#pragma once

typedef bool (*sig_vk_abort_cb)(void * data);

struct sig_vk_abort_gate {
    sig_vk_abort_cb cb       = nullptr;
    void *          data     = nullptr;
    bool            observed = false;
    bool            logged   = false;   // R24/ERR — um log por arm

    // Grafo novo => decisão nova. O pedido pendente (se ainda houver) é
    // observado no primeiro poll do grafo seguinte.
    void reset() { observed = false; logged = false; }

    // Consulta o pedido. Pegajoso: uma vez verdadeiro, continua verdadeiro sem
    // chamar o callback de novo.
    bool poll() {
        if (observed) {
            return true;
        }
        bool ab = false;
        if (cb != nullptr) {
            ab = cb(data);
            // R24/ERR — rastro DECISIVO: o retorno do callback no 1o check do
            // arm. Sem isto, "gate abortou" e "scheduler falhou depois de
            // armar" sao indistinguiveis no bruto.
            if (!logged) {
                logged = true;
                // R25/FIX — rastro de DIAG apenas; o header é compartilhado com
                // o build NORMAL, onde o log por poll custa sem consumidor.
#ifdef SIG_WHISPER_DUMP_HOOK
                fprintf(stderr, "SIGABORT ev=poll armed=1 ret=%d\n", (int) ab);
                fflush(stderr);
#endif
            }
        }
        if (ab) {
            observed = true;
            return true;
        }
        return false;
    }

    bool armed() const { return cb != nullptr; }
};

// Fronteiras seguras onde o pedido é consultado, na ordem em que o backend as
// atravessa. Sempre ANTES de gravar a unidade seguinte ou de submeter.
enum sig_vk_abort_boundary {
    SIG_VK_ABORT_BOUNDARY_GRAPH_ENTRY   = 0, // antes de gravar o 1º comando do grafo
    SIG_VK_ABORT_BOUNDARY_BETWEEN_UNITS = 1, // entre unidades (nó/fusão) do lote
    SIG_VK_ABORT_BOUNDARY_BEFORE_SUBMIT = 2, // imediatamente antes de queue.submit
};

enum sig_vk_unit_action {
    SIG_VK_UNIT_CONTINUE = 0, // segue gravando a próxima unidade
    SIG_VK_UNIT_SUBMIT   = 1, // política de submit deste fork pediu fechar o lote
    SIG_VK_UNIT_ABORT    = 2, // pedido observado: nada novo pode ser submetido
};

// Decisão de uma unidade: abort vence submit; sem pedido, a política de submit
// do backend (calculada pelo chamador, inalterada) decide.
inline sig_vk_unit_action sig_vk_next_action(sig_vk_abort_gate & gate, bool submit_due) {
    if (gate.poll()) {
        return SIG_VK_UNIT_ABORT;
    }
    return submit_due ? SIG_VK_UNIT_SUBMIT : SIG_VK_UNIT_CONTINUE;
}
