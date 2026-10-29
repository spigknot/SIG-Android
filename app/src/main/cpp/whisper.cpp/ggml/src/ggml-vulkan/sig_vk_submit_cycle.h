// R29/P0 — ADAPTADOR DO CICLO REAL DE SUBMIT (admissão → commit → emissão).
//
// Por que existe: na R28 a admissão, o witness e o cancelamento viviam em três
// lugares diferentes de `ggml_vk_submit`, e nem `mark_submitted` nem
// `configure_domain` eram chamados pelo caminho de produção. O resultado medido
// pela revisão: o ledger registrava reservas que nunca viravam envio, o witness
// aceitava reserva como "trabalho em voo" e o cancelamento só existia no build
// DIAG (dentro de SIG_WHISPER_DUMP_HOOK).
//
// Este adaptador é o ÚNICO caminho de decisão do submit, nos dois builds:
//
//   admit()      — admissão (+ reserva no ledger, quando há DIAG). Pode esperar
//                  (K) e pode ser negada por cancelamento. NÃO emite nada.
//   precommit()  — fronteira final, chamada com o lock da fila retido e
//                  imediatamente antes de `queue.submit`. Reconsulta o
//                  cancelamento; negado, a reserva vira ABANDONED e NADA é
//                  emitido (nem valor de timeline é consumido).
//   commit()     — chamada DEPOIS do `queue.submit` retornar, na mesma região
//                  serializada. A unidade só aqui vira SUBMITTED e recebe o
//                  valor que o próprio envio sinalizou.
//   fail()       — o envio lançou/falhou: efeito INCERTO → quarentena (FAILED).
//
// Sem ledger (build NORMAL) o adaptador continua sendo o caminho único do
// cancelamento e da recusa por K não suportado — a proteção não fica só no DIAG.
#pragma once

#include "sig_vk_ledger.h"

namespace sig_vk_cycle {

// R30/A2 - quatro resultados DIFERENTES, que a R29 colapsava em dois:
//   SUBMITTED : trabalho util emitido na fila (conta divida de dreno);
//   MARKER    : apenas um marcador de sincronizacao (fence) foi enviado - ha
//               ciclo de fence a acompanhar, mas NAO e inferencia util;
//   NO_WORK   : nada foi emitido (nem marcador) - nao e cancelamento;
//   CANCELLED : pedido de cancelamento observado antes do commit, nada emitido.
enum class Outcome { ADMITTED, SUBMITTED, MARKER, NO_WORK, CANCELLED, REJECTED };

inline bool emitted(Outcome o) {
    return o == Outcome::SUBMITTED || o == Outcome::MARKER;
}

inline bool useful(Outcome o) {
    return o == Outcome::SUBMITTED;
}

/** R30/A1 - DIVIDA DE DRENO: so ACUMULA. Um resultado posterior (cancelado,
 *  sem trabalho, marcador) nunca apaga trabalho anterior ainda nao provado.
 *  A limpeza e ato separado, reservado a prova real de sincronizacao. */
inline bool debt_after(bool debt, Outcome o) {
    return debt || emitted(o);
}

/**
 * R32/C1 — índice cujo ULTIMO sinal pertence ao DIAG, ou -1.
 *
 * A R31 usava `!tl_signal_vals.empty()` e depois `.back()`, o que alcanca o
 * ultimo sinal de QUALQUER fila: numa fila fora do dominio, o sinal proprio de
 * transferencia era sobrescrito e o contador DIAG ainda era incrementado.
 * O sinal so existe se foi anexado NAQUELA montagem (ultima SubmitInfo, dentro
 * do dominio, com semaforo e timeline ligados).
 */
inline int tl_slot_owner(bool tl_on, bool has_semaphore, bool in_domain,
                         int idx, int reserve) {
    if (!tl_on || !has_semaphore || !in_domain) {
        return -1;
    }
    if (reserve <= 0 || idx != reserve - 1) {
        return -1;
    }
    return idx;
}

inline const char * outcome_name(Outcome o) {
    switch (o) {
        case Outcome::ADMITTED:  return "ADMITTED";
        case Outcome::SUBMITTED: return "SUBMITTED";
        case Outcome::MARKER:    return "MARKER";
        case Outcome::NO_WORK:   return "NO_WORK";
        case Outcome::CANCELLED: return "CANCELLED";
        case Outcome::REJECTED:  return "REJECTED";
    }
    return "?";
}

// Vínculo do pedido de cancelamento com o DONO deste envio. `poll` é a leitura
// do gate do backend context que produziu o submit (nunca "o último gate
// instalado"); `ident` viaja com a unidade para que a amostra tardia do witness
// não seja atribuída a outro job.
struct Cancel {
    sig_vk_ledger::CancelFn   poll;    // vazio = gate não armado
    sig_vk_ledger::Ident      ident;

    bool armed() const     { return static_cast<bool>(poll); }
    bool requested() const { return poll ? poll() : false; }
};

struct Request {
    uint64_t device      = 0;
    uint64_t queue       = 0;
    uint64_t k           = 0;
    sig_vk_ledger::Resource resource = sig_vk_ledger::Resource::TIMELINE;
    uint64_t handle      = 0;   // semáforo/fence do envio
    uint64_t planned     = 0;   // valor conhecido antes do envio (fence); timeline = 0
    uint64_t graph_gen   = 0;
    int64_t  deadline_us = 0;
};

struct Ticket {
    Outcome               outcome      = Outcome::CANCELLED;
    sig_vk_ledger::Admit  admit        = sig_vk_ledger::Admit::CANCELLED;
    uint64_t              id           = 0;
    bool                  ledger       = false;  // a unidade existe no ledger
    // R30/A3 - o envio ACONTECEU mas a contabilidade falhou: o fato nao pode
    // virar "nada enviado". Quem chama mantem a divida e registra quarentena.
    bool                  ledger_failed = false;
};

/**
 * Admissão. `led` pode ser nullptr (NORMAL): aí só há recusa por cancelamento
 * ou por K > 0 (o build não carrega o ledger, então K não pode ser aplicado).
 */
inline Ticket admit(sig_vk_ledger::Ledger * led, const Request & r,
                    const sig_vk_ledger::WaitFn & wait_fn, const Cancel & c) {
    Ticket t;
    if (c.requested()) {
        t.outcome = Outcome::CANCELLED;
        t.admit   = sig_vk_ledger::Admit::CANCELLED;
        return t;
    }
    if (r.k > 0 && (led == nullptr || !wait_fn)) {
        // Pedido de K num build/caminho sem observador de conclusão: recusar
        // ANTES da inferência, em vez de anunciar configuração inexistente.
        t.outcome = Outcome::REJECTED;
        t.admit   = sig_vk_ledger::Admit::UNSUPPORTED_DOMAIN;
        return t;
    }
    if (led == nullptr) {
        t.outcome = Outcome::ADMITTED;     // NORMAL: nada a registrar
        t.admit   = sig_vk_ledger::Admit::OK;
        return t;
    }
    t.admit = led->reserve(r.queue, r.k, wait_fn, 0, r.resource, r.handle,
                           r.planned, r.graph_gen, r.device, r.deadline_us,
                           c.poll, &t.id, c.ident);
    if (t.admit == sig_vk_ledger::Admit::OK) {
        t.ledger  = true;
        t.outcome = Outcome::ADMITTED;
        return t;
    }
    t.id      = 0;
    t.ledger  = false;
    t.outcome = (t.admit == sig_vk_ledger::Admit::CANCELLED) ? Outcome::CANCELLED
                                                             : Outcome::REJECTED;
    return t;
}

/**
 * Fronteira final, com o lock da fila JÁ retido e nada emitido ainda.
 * Devolve false quando o pedido venceu a corrida: a reserva é abandonada e o
 * chamador NÃO pode submeter.
 */
inline bool precommit(Ticket & t, sig_vk_ledger::Ledger * led, const Cancel & c) {
    if (t.outcome != Outcome::ADMITTED) {
        return false;
    }
    if (c.requested()) {
        if (led != nullptr && t.ledger && t.id != 0) {
            led->abandon(t.id);            // reserva comprovadamente não emitida
        }
        t.outcome = Outcome::CANCELLED;
        t.ledger  = false;
        t.id      = 0;
        return false;
    }
    return true;
}

/** Após `queue.submit` efetivado: a unidade passa a SUBMITTED com o valor real. */
inline Outcome commit(Ticket & t, sig_vk_ledger::Ledger * led, uint64_t value,
                      bool marker = false) {
    if (t.outcome != Outcome::ADMITTED) {
        return t.outcome;
    }
    if (led != nullptr && t.ledger) {
        if (!led->mark_submitted(t.id, value)) {
            // R30/A3 - o envio JA aconteceu. Se a unidade ja estava DONE, a
            // contabilidade esta coerente (outra prova a cobriu); qualquer outro
            // estado significa falha de registro: o fato de ter emitido e
            // preservado e a quarentena e registrada, em vez de anunciar
            // "cancelado" para algo que esta na fila.
            sig_vk_ledger::State st = sig_vk_ledger::State::FAILED;
            const bool achou = led->state_of(t.id, &st);
            if (!achou || st != sig_vk_ledger::State::DONE) {
                t.ledger_failed = true;
                led->rollback(t.id);          // quarentena: nao recicla recurso
                // R31/B2 - envenena o DOMINIO: sem isso a proxima admissao
                // (inclusive K=0) passaria por cima de uma emissao nao
                // contabilizada. Recuperacao exige reset explicito e seguro.
                led->poison("mark_submitted falhou apos emissao");
            }
        }
    }
    t.outcome = marker ? Outcome::MARKER : Outcome::SUBMITTED;
    return t.outcome;
}

/** O envio falhou depois da reserva: efeito incerto → quarentena. */
inline void fail(Ticket & t, sig_vk_ledger::Ledger * led) {
    if (led != nullptr && t.ledger && t.id != 0) {
        led->rollback(t.id);
    }
    t.ledger  = false;
    t.outcome = Outcome::REJECTED;
}

} // namespace sig_vk_cycle
