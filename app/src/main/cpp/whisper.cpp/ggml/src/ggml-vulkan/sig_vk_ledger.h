// R25/GPU-K1 — LEDGER DE SUBMISSÕES POR RECURSO, com admissão e conclusão.
//
// Defeito que isto corrige: o "K" da R21/R22 era um contador GLOBAL
// (`sig_vk_inflight::outstanding`) e a espera era `queue.waitIdle()` — que
// espera TODO o trabalho da fila, não a unidade mais antiga, e não distingue
// fila, geração de fence nem resultado. Consequências medidas na revisão da
// R24: conclusão fora de ordem liberava slot sem prova, fence reciclada podia
// ser contada como conclusão da unidade atual, e falha de espera liberava o
// slot (o trabalho continuava em voo).
//
// Contrato (K1):
//   * cada submit ÚTIL vira uma UNIDADE com submit_id, fila, recurso e o valor
//     que aquela emissão sinaliza;
//   * admissão acontece ANTES do submit: com `pendentes >= K` no domínio
//     (fila), espera a unidade MAIS ANTIGA daquele domínio;
//   * conclusão só é registrada em SUCESSO; NOT_READY/erro NÃO liberam slot;
//   * geração de fence reciclada não é prova da unidade atual → a unidade é
//     marcada STALE (não concluída) e o chamador decide (fallback explícito);
//   * conclusão fora de ordem não regride estado nem libera outra fila;
//   * recursos permanecem vivos na unidade até a conclusão.
//
// R29/P0 — CICLO REAL DO SUBMIT. A revisão da R28 provou que o caminho de
// produção nunca chamava `mark_submitted()`/`configure_domain()`, que a busca da
// unidade mais antiga ignorava SUBMITTED (→ INCONSISTENT na admissão seguinte) e
// que `note_completion`/`note_queue_idle` também não concluíam SUBMITTED, de modo
// que incluir SUBMITTED na busca entrava em laço. Corrigido aqui:
//
//   1. RESERVED/PENDING — slot reservado, NADA emitido. Ocupa slot (conta para
//      a admissão) mas NÃO é testemunha de trabalho em voo: o valor/timeline
//      ainda não existe, então nenhuma prova de GPU pode concluí-la.
//   2. SUBMITTED — `queue.submit` efetivado; carrega o valor que o próprio envio
//      sinaliza. É a ÚNICA unidade que o witness considera em voo.
//   3. DONE — prova válida (prefixo do recurso efetivamente coberto).
//   4. ABANDONED — reserva comprovadamente nunca emitida; libera slot sem
//      contar conclusão.
//   5. FAILED/STALE — incerteza/falha tratada como QUARENTENA: não finge
//      conclusão, não recicla recurso inseguro e bloqueia nova admissão do
//      domínio (Admit::QUARANTINE) até o chamador encerrar o domínio.
//
// Espera entre emissores: se o candidato mais antigo é PENDING (reserva ainda
// não emitida por OUTRO emissor), a admissão NÃO espera valor de GPU — espera a
// mudança de estado (commit/abandono) com prazo e cancelamento, sinalizada por
// condição própria. O mutex do ledger nunca é mantido durante a espera externa.
//
// Sem dependência de Vulkan: o teste de host exercita a política com
// `WaitFn`/`CancelFn` injetados (o backend passa getSemaphoreCounterValue e o
// gate do backend context dono do envio).
#pragma once

#include <cstdint>
#include <condition_variable>
#include <functional>
#include <map>
#include <mutex>
#include <chrono>
#include <string>
#include <vector>

namespace sig_vk_ledger {

// R28/A1 — estados EXPLÍCITOS: reserva, envio, conclusão, falha incerta e
// abandono (reserva nunca emitida, libera slot sem fingir conclusão).
enum class State { PENDING, SUBMITTED, DONE, FAILED, STALE, ABANDONED };

enum class Wait { SUCCESS, NOT_READY, ERROR };

// Tipo do recurso de conclusão. Fence e timeline têm semânticas DIFERENTES:
// fence é reciclável (valor = geração) e timeline é monotônica (valor = sinal).
enum class Resource { FENCE, TIMELINE };

// R29/P0 — identidade do envio. Não é decoração: o witness só vale para a
// unidade do owner/tentativa vivos, e uma amostra tardia não pode ser atribuída
// ao job seguinte. `owner_hash` = FNV-1a do nome do owner (0 = não vinculado).
struct Ident {
    uint64_t owner_hash = 0;
    uint64_t job        = 0;
    int32_t  attempt    = 0;
    uint64_t gen        = 0;

    bool operator==(const Ident & o) const {
        return owner_hash == o.owner_hash && job == o.job && attempt == o.attempt && gen == o.gen;
    }
};

struct Unit {
    uint64_t submit_id   = 0;
    uint64_t epoch       = 0;   // R27/K — época do ledger; ID NUNCA é reutilizado
    uint64_t device      = 0;   // device real (não "fila 0 global")
    uint64_t queue       = 0;
    Resource resource    = Resource::FENCE;
    uint64_t fence       = 0;   // handle: VkFence ou VkSemaphore (como inteiro)
    uint64_t fence_gen   = 0;   // geração da fence OU valor da timeline (0 = não emitida)
    uint64_t planned     = 0;   // valor pretendido, informado na reserva
    uint64_t graph_gen   = 0;   // geração do contexto/grafo (diagnóstico)
    Ident    ident      {};     // R29 — dono/tentativa do envio
    State    state       = State::PENDING;
};

// Tipos de injeção do teste/backend: espera por unidade e consulta do pedido
// de cancelamento. No escopo do namespace para que o adaptador do ciclo
// (sig_vk_submit_cycle.h) os nomeie sem depender de `Ledger`.
using WaitFn   = std::function<Wait(const Unit &)>;
using CancelFn = std::function<bool()>;

// R27/K — resultado TIPADO da admissão. O chamador não pode tratar "sem prova"
// como sucesso: TIMEOUT/ERROR/STALE/QUARANTINE precisam impedir a emissão.
enum class Admit { OK, TIMEOUT, ERROR, STALE, INCONSISTENT, CANCELLED, UNSUPPORTED_DOMAIN, QUARANTINE };

struct Stats {
    uint64_t submits         = 0;   // unidades registradas (reservas)
    uint64_t admission_waits = 0;   // vezes que a admissão precisou esperar
    uint64_t completed       = 0;   // unidades concluídas com SUCESSO
    uint64_t stale           = 0;   // geração reciclada detectada
    uint64_t errors          = 0;   // espera com erro (slot NÃO liberado)
    uint64_t not_ready       = 0;   // espera expirou sem conclusão (slot mantido)
    uint64_t inconsistent    = 0;   // slot ocupado sem candidato
    uint64_t max_pending     = 0;   // pico de pendentes por domínio
    uint64_t epochs          = 0;   // épocas encerradas (reset com fila vazia)
    uint64_t abandoned       = 0;   // reservas nunca emitidas, liberadas
    uint64_t submitted       = 0;   // R29 — reservas que viraram envio efetivo
    uint64_t quarantined     = 0;   // R29 — admissões recusadas por quarentena
    uint64_t cancelled       = 0;   // R29 — admissões/commits negados por cancelamento
    uint64_t peer_waits      = 0;   // R29 — esperas por reserva de outro emissor
    uint64_t proven          = 0;   // R31 — conclusões por prefixo de marcador
};

class Ledger {
public:
    using WaitFn   = sig_vk_ledger::WaitFn;
    using CancelFn = sig_vk_ledger::CancelFn;

    /** Registra um submit JÁ efetivado. Compatível com a chamada da R25. */
    uint64_t note_submit(uint64_t queue, uint64_t fence, uint64_t fence_gen,
                         uint64_t graph_gen = 0) {
        return note_submit_as(queue, Resource::FENCE, fence, fence_gen, graph_gen);
    }

    /** Registra um submit cujo recurso de conclusão é uma timeline (semáforo). */
    uint64_t note_submit_timeline(uint64_t queue, uint64_t semaphore, uint64_t value,
                                  uint64_t graph_gen = 0) {
        return note_submit_as(queue, Resource::TIMELINE, semaphore, value, graph_gen);
    }

    uint64_t note_submit_as(uint64_t queue, Resource resource, uint64_t handle,
                            uint64_t value, uint64_t graph_gen = 0,
                            uint64_t device = 0) {
        std::lock_guard<std::mutex> lk(m_);
        const uint64_t id = note_locked(queue, resource, handle, value, graph_gen, device, Ident{});
        // note_* registra envio JÁ efetivado: nasce SUBMITTED, nunca PENDING.
        for (auto & u : units_) {
            if (u.submit_id == id) {
                u.state = State::SUBMITTED;
                stats_.submitted++;
                break;
            }
        }
        const uint64_t p = pending_locked(queue);
        if (p > stats_.max_pending) {
            stats_.max_pending = p;
        }
        cv_.notify_all();
        return id;
    }

    /**
     * R27/K — ADMISSÃO + RESERVA ATÔMICA (item 5 do contrato): espera de forma
     * tipada e, quando há slot, REGISTRA a reserva na mesma seção crítica. Dois
     * emissores concorrentes não conseguem reservar o último slot: quem chega
     * depois encontra pendentes >= k e espera.
     *
     * A unidade nasce PENDING: NADA foi emitido ainda. O valor real do recurso
     * chega em `mark_submitted(id, valor)` depois do `queue.submit` efetivo.
     *
     * `deadline_us` limita a espera total (0 = sem limite). `cancel_fn`, quando
     * fornecida, é consultada antes de conceder vaga, depois de cada espera e no
     * ponto final de commit — cancelar vence reservar.
     *
     * Em falha (TIMEOUT/ERROR/STALE/INCONSISTENT/QUARANTINE/CANCELLED) NÃO
     * registra nada e devolve o motivo — o chamador deve impedir o submit.
     */
    Admit reserve(uint64_t queue, uint64_t k, const WaitFn & wait_fn,
                  uint64_t fence_reset_gen, Resource resource, uint64_t handle,
                  uint64_t value, uint64_t graph_gen, uint64_t device,
                  int64_t deadline_us, const CancelFn & cancel_fn,
                  uint64_t * out_id, Ident ident = Ident{}) {
        if (out_id != nullptr) {
            *out_id = 0;
        }
        {
            std::lock_guard<std::mutex> lk(m_);
            // R29 — o DOMÍNIO é o device; `queue == 0` no configure significa
            // "qualquer fila deste device" (compute e transfer são filas
            // distintas do MESMO device e ambas são admissíveis).
            if (dom_configurado_ &&
                (device != dom_device_ || (dom_queue_ != 0 && queue != dom_queue_))) {
                return Admit::UNSUPPORTED_DOMAIN;
            }
        }
        const int64_t t_start = now_us();
        for (;;) {
            // R28/A1 — cancelamento observado ANTES de conceder vaga: nenhuma
            // unidade nova é registrada (nem com k == 0).
            if (cancel_fn && cancel_fn()) {
                std::lock_guard<std::mutex> lk(m_);
                stats_.cancelled++;
                return Admit::CANCELLED;
            }
            if (k > 0 && !wait_fn) {
                // K sem observador de conclusão não pode ser aplicado: recusar
                // é melhor que anunciar uma configuração que não existe.
                return Admit::UNSUPPORTED_DOMAIN;
            }
            Unit cand;
            {
                std::lock_guard<std::mutex> lk(m_);
                // R31/B2 — domínio envenenado: nenhuma admissão nova, nem K=0.
                if (poisoned_) {
                    stats_.quarantined++;
                    return Admit::QUARANTINE;
                }
                // R30/A4 - QUARENTENA ANTES DA VAGA: uma unidade FAILED/STALE no
                // dominio inseguro bloqueia nova admissao INDEPENDENTE de K.
                // Desativar o limitador (K=0) nao limpa uma falha; quem limpa e
                // reset/invalidacao explicita, nunca "tem vaga".
                for (const auto & q : units_) {
                    if (q.queue == queue &&
                        (q.state == State::FAILED || q.state == State::STALE)) {
                        stats_.quarantined++;
                        return Admit::QUARANTINE;
                    }
                }
                if (k == 0 || pending_locked(queue) < k) {
                    if (cancel_fn && cancel_fn()) {
                        stats_.cancelled++;
                        return Admit::CANCELLED;   // commit negado no ponto final
                    }
                    const uint64_t id = note_locked(queue, resource, handle, value,
                                                    graph_gen, device, ident);
                    if (out_id != nullptr) {
                        *out_id = id;
                    }
                    return Admit::OK;
                }
                stats_.admission_waits++;
                Unit * mais_antiga = oldest_occupying_locked(queue);
                if (mais_antiga == nullptr) {
                    stats_.inconsistent++;
                    return Admit::INCONSISTENT;
                }
                if (mais_antiga->state == State::FAILED || mais_antiga->state == State::STALE) {
                    // Quarentena: o recurso da frente está em estado inseguro.
                    // Nenhuma unidade nova entra atrás dele.
                    stats_.quarantined++;
                    return Admit::QUARANTINE;
                }
                if (mais_antiga->resource == Resource::FENCE && fence_reset_gen != 0 &&
                    mais_antiga->fence_gen != 0 && mais_antiga->fence_gen <= fence_reset_gen) {
                    mais_antiga->state = State::STALE;
                    stats_.stale++;
                    cv_.notify_all();
                    return Admit::STALE;
                }
                cand = *mais_antiga;
            }

            if (cand.state == State::PENDING) {
                // R29/P0 — a frente é uma reserva de OUTRO emissor que ainda não
                // foi emitida. Esperar valor de GPU aqui seria esperar por algo
                // que nunca foi enviado. Espera-se a mudança de estado, com
                // prazo e cancelamento, e o mutex NÃO fica retido.
                std::unique_lock<std::mutex> lk(m_);
                stats_.peer_waits++;
                cv_.wait_for(lk, std::chrono::milliseconds(2), [&] {
                    const Unit * u = find_locked(cand.submit_id, cand.epoch);
                    return u == nullptr || u->state != State::PENDING;
                });
            } else {
                // SUBMITTED: a prova é do recurso que o envio sinalizou.
                const Wait r = wait_fn(cand);
                {
                    std::lock_guard<std::mutex> lk(m_);
                    if (r == Wait::SUCCESS) {
                        Unit * u = find_locked(cand.submit_id, cand.epoch);
                        if (u != nullptr && u->state == State::SUBMITTED) {
                            complete_covered_locked(*u);
                            cv_.notify_all();
                        }
                    } else if (r == Wait::ERROR) {
                        stats_.errors++;
                        return Admit::ERROR;
                    } else {
                        stats_.not_ready++;
                    }
                }
            }
            // R28/A1 — reconsulta DEPOIS da espera (inclusive quando ela
            // retornou SUCCESS) e no ponto de commit: cancelar vence reservar.
            if (cancel_fn && cancel_fn()) {
                std::lock_guard<std::mutex> lk(m_);
                stats_.cancelled++;
                return Admit::CANCELLED;
            }
            if (deadline_us > 0 && (now_us() - t_start) >= deadline_us) {
                return Admit::TIMEOUT;
            }
        }
    }

    /**
     * R29/P0 — COMMIT: a unidade foi emitida na fila. Deve ser chamado com o
     * `queue.submit` JÁ efetivado, na MESMA região serializada em que o valor do
     * timeline foi atribuído. `value == 0` herda o valor planejado na reserva
     * (caso da fence, cuja geração é conhecida antes do envio).
     *
     * Devolve false se a unidade não está mais em PENDING (cancelada, abandonada,
     * quarentenada): o chamador NÃO deve anunciar envio.
     */
    bool mark_submitted(uint64_t submit_id, uint64_t value = 0) {
        std::lock_guard<std::mutex> lk(m_);
        for (auto & u : units_) {
            if (u.submit_id == submit_id) {
                if (u.state != State::PENDING) {
                    return false;
                }
                u.fence_gen = (value != 0) ? value : u.planned;
                u.state     = State::SUBMITTED;
                stats_.submitted++;
                cv_.notify_all();
                return true;
            }
        }
        return false;
    }

    /** R27/K — desfaz a unidade cujo envio falhou (efeito incerto = quarentena). */
    bool rollback(uint64_t submit_id) {
        std::lock_guard<std::mutex> lk(m_);
        for (auto & u : units_) {
            if (u.submit_id == submit_id) {
                if (u.state == State::PENDING || u.state == State::SUBMITTED) {
                    u.state = State::FAILED;
                    stats_.errors++;
                    cv_.notify_all();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * R29/P0 — reserva NUNCA emitida: libera o slot SEM contar conclusão.
     * Só vale para PENDING; uma unidade já emitida não pode ser "abandonada"
     * (isso apagaria trabalho em voo).
     */
    bool abandon(uint64_t submit_id) {
        std::lock_guard<std::mutex> lk(m_);
        for (auto & u : units_) {
            if (u.submit_id == submit_id && u.state == State::PENDING) {
                u.state = State::ABANDONED;
                stats_.abandoned++;
                cv_.notify_all();
                return true;
            }
        }
        return false;
    }

    /**
     * R29/P0 — conclusão explícita (caminho de sync real). Só uma unidade
     * EMITIDA pode ser concluída: concluir uma reserva seria fingir que algo
     * que nunca foi enviado terminou.
     */
    bool note_completion(uint64_t submit_id, bool ok) {
        std::lock_guard<std::mutex> lk(m_);
        for (auto & u : units_) {
            if (u.submit_id == submit_id) {
                if (u.state != State::SUBMITTED) {
                    return false;
                }
                u.state = ok ? State::DONE : State::FAILED;
                if (ok) {
                    stats_.completed++;
                } else {
                    stats_.errors++;
                }
                cv_.notify_all();
                return true;
            }
        }
        return false;
    }

    /**
     * Admissão K1 "solta" (compatibilidade com a chamada da R25): NÃO registra
     * unidade. Mantida para consumidores antigos; o caminho de produção usa
     * `reserve` + `mark_submitted`.
     */
    bool admit(uint64_t queue, uint64_t k, const WaitFn & wait_fn,
               uint64_t fence_reset_gen = 0) {
        Unit cand;
        {
            std::lock_guard<std::mutex> lk(m_);
            if (k == 0) {
                return true;
            }
            if (pending_locked(queue) < k) {
                return true;
            }
            stats_.admission_waits++;
            Unit * mais_antiga = oldest_occupying_locked(queue);
            if (mais_antiga == nullptr) {
                stats_.inconsistent++;
                return false;
            }
            if (mais_antiga->state == State::FAILED || mais_antiga->state == State::STALE) {
                stats_.quarantined++;
                return false;
            }
            if (mais_antiga->resource == Resource::FENCE && fence_reset_gen != 0 &&
                mais_antiga->fence_gen != 0 && mais_antiga->fence_gen <= fence_reset_gen) {
                mais_antiga->state = State::STALE;
                stats_.stale++;
                cv_.notify_all();
                return false;
            }
            if (mais_antiga->state == State::PENDING) {
                return false;   // não emitida: não há o que esperar na GPU
            }
            cand = *mais_antiga;
        }
        const Wait r = wait_fn(cand);
        std::lock_guard<std::mutex> lk(m_);
        if (r == Wait::SUCCESS) {
            Unit * u = find_locked(cand.submit_id, cand.epoch);
            if (u != nullptr && u->state == State::SUBMITTED) {
                complete_covered_locked(*u);
                cv_.notify_all();
            }
            return pending_locked(queue) < k;
        }
        if (r == Wait::NOT_READY) {
            stats_.not_ready++;
            return false;
        }
        stats_.errors++;
        return false;
    }

    /**
     * R28/A1 — DOMÍNIO VERIFICADO: uma fila por device. Chamado uma vez no
     * init; qualquer unidade de outro (device,fila) é recusada explicitamente
     * em vez de ser tratada como "fila 0 universal".
     */
    void configure_domain(uint64_t device, uint64_t queue) {
        std::lock_guard<std::mutex> lk(m_);
        dom_device_ = device;
        dom_queue_ = queue;
        dom_configurado_ = true;
    }

    bool domain_ok(uint64_t device, uint64_t queue) {
        std::lock_guard<std::mutex> lk(m_);
        return !dom_configurado_ || (device == dom_device_ && queue == dom_queue_);
    }

    /**
     * R29/P0 — WITNESS: fotografia de uma unidade EMITIDA (SUBMITTED) ainda não
     * provada, com o valor observado do recurso NO INSTANTE (sem esperar, sem
     * alterar nada). Reserva não emitida NÃO é unidade em voo: devolve inválido.
     *
     * `observed < 0` significa INDISPONÍVEL (erro de leitura) — nunca zero.
     * `owner_hash`/`gen` filtram a identidade: uma amostra de outro job não pode
     * ser usada como witness deste.
     */
    struct Witness {
        bool valid = false;
        uint64_t submit_id = 0, device = 0, queue = 0, epoch = 0;
        Resource resource = Resource::FENCE;
        uint64_t handle = 0, target = 0;
        int64_t observed = -1;
        bool completed = false;
        State state = State::DONE;
        Ident ident {};
        int64_t ts_us = 0;
    };

    using ObserveFn = std::function<int64_t(uint64_t handle, Resource)>;

    Witness witness_pending(const ObserveFn & observe, uint64_t owner_hash = 0,
                            uint64_t gen = 0) {
        Witness w;
        Unit u;
        {
            std::lock_guard<std::mutex> lk(m_);
            Unit * p = nullptr;
            for (auto & x : units_) {
                if (x.state != State::SUBMITTED) {
                    continue;                      // reserva nunca é "em voo"
                }
                if (owner_hash != 0 && x.ident.owner_hash != owner_hash) {
                    continue;                      // unidade de outro dono
                }
                if (gen != 0 && x.ident.gen != 0 && x.ident.gen != gen) {
                    continue;
                }
                p = &x;
                break;
            }
            if (p == nullptr) {
                return w;                       // nada emitido e não provado
            }
            u = *p;
        }
        w.valid = true;
        w.submit_id = u.submit_id;
        w.device = u.device;
        w.queue = u.queue;
        w.epoch = u.epoch;
        w.resource = u.resource;
        w.handle = u.fence;
        w.target = u.fence_gen;
        w.state = u.state;
        w.ident = u.ident;
        w.ts_us = now_us();
        if (observe) {
            w.observed = observe(u.fence, u.resource);   // < 0 = indisponível
        }
        w.completed = (w.observed >= 0) && ((uint64_t) w.observed >= w.target);
        return w;
    }

    /** R28/A2 — RECONCILIAÇÃO: saldo por estado até o último submit. */
    struct Recon {
        uint64_t completed = 0, submitted = 0, pending = 0;
        uint64_t failed = 0, abandoned = 0, stale = 0;
        uint64_t total = 0;

        bool fecha() const {
            return completed + submitted + pending + failed + abandoned + stale == total;
        }
    };

    Recon reconcile() {
        std::lock_guard<std::mutex> lk(m_);
        Recon r;
        for (const auto & u : units_) {
            switch (u.state) {
                case State::DONE:      r.completed++; break;
                case State::SUBMITTED: r.submitted++; break;
                case State::PENDING:   r.pending++;   break;
                case State::FAILED:    r.failed++;    break;
                case State::ABANDONED: r.abandoned++; break;
                case State::STALE:     r.stale++;     break;
            }
            r.total++;
        }
        return r;
    }

    uint64_t pending(uint64_t queue) {
        std::lock_guard<std::mutex> lk(m_);
        return pending_locked(queue);
    }

    /** R31/B1 — época corrente (para capturar o limite da prova). */
    uint64_t current_epoch() {
        std::lock_guard<std::mutex> lk(m_);
        return epoch_;
    }

    uint64_t pending_total() {
        std::lock_guard<std::mutex> lk(m_);
        return pending_locked_all();
    }

    Stats stats() {
        std::lock_guard<std::mutex> lk(m_);
        return stats_;
    }

    /** R30/A3 - estado observavel de uma unidade (0 = desconhecida). */
    bool state_of(uint64_t submit_id, State * out) {
        std::lock_guard<std::mutex> lk(m_);
        for (const auto & u : units_) {
            if (u.submit_id == submit_id) {
                if (out != nullptr) {
                    *out = u.state;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * R31/B1 — LIMITE DA PROVA: maior valor efetivamente emitido na fila, no
     * instante em que a marcação de sincronização é submetida. Capturado sob a
     * MESMA exclusão que ordena os submits (queue_mutex), ele define até onde a
     * fence do marcador cobre. Não é max(id) de reserva: uma unidade reservada
     * antes e emitida depois fica de fora, que é o caso que a R30 errava.
     */
    uint64_t last_emitted_value(uint64_t queue, uint64_t epoch) {
        std::lock_guard<std::mutex> lk(m_);
        uint64_t v = 0;
        for (const auto & u : units_) {
            if (u.queue == queue && u.epoch == epoch && u.state == State::SUBMITTED &&
                u.fence_gen > v) {
                v = u.fence_gen;
            }
        }
        return v;
    }

    /**
     * R31/B1 — conclui SOMENTE o prefixo coberto pela prova: unidades da mesma
     * fila e época com valor <= `value`. `value == 0` não prova nada.
     * Devolve quantas unidades foram concluídas.
     */
    uint64_t note_proven(uint64_t queue, uint64_t value, uint64_t epoch) {
        if (value == 0) {
            return 0;
        }
        std::lock_guard<std::mutex> lk(m_);
        uint64_t n = 0;
        for (auto & u : units_) {
            if (u.queue != queue || u.epoch != epoch) {
                continue;
            }
            if (u.state == State::SUBMITTED && u.fence_gen != 0 && u.fence_gen <= value) {
                u.state = State::DONE;
                stats_.completed++;
                n++;
            }
        }
        if (n != 0) {
            cv_.notify_all();
        }
        return n;
    }

    /**
     * R31/B2 — ENVENENAMENTO DO DOMÍNIO: a contabilidade falhou depois de um
     * envio real. Nenhuma admissão nova é concedida (nem com K=0) até um reset
     * explícito e seguro; o recurso envolvido fica em quarentena.
     */
    void poison(const char * motivo) {
        std::lock_guard<std::mutex> lk(m_);
        poisoned_ = true;
        if (motivo != nullptr) {
            poison_reason_ = motivo;
        }
        stats_.quarantined++;
        cv_.notify_all();
    }

    bool poisoned() {
        std::lock_guard<std::mutex> lk(m_);
        return poisoned_;
    }

    /**
     * R32/C2 — ENVENENAMENTO É TERMINAL PARA ESTA INSTÂNCIA.
     *
     * A R31 permitia limpar quando o saldo parecia vazio (PENDING/SUBMITTED/
     * FAILED/STALE). No caso ABANDONED/ausente a emissão não contabilizada não
     * aparece nesse saldo, então a limpeza acontecia sem nenhuma observação da
     * GPU e reabria admissão. Uma emissão que não pôde ser contabilizada é
     * incerteza de domínio: a recuperação é um domínio NOVO (processo/contexto
     * novos), nunca um flag.
     *
     * Mantido como função explícita que RECUSA, para que o chamador não volte a
     * acreditar em limpeza implícita.
     */
    bool clear_poison() {
        return false;
    }

    const char * poison_reason() {
        std::lock_guard<std::mutex> lk(m_);
        return poison_reason_.empty() ? "" : poison_reason_.c_str();
    }

    /**
     * Fila comprovadamente ociosa (waitIdle real): conclui as unidades EMITIDAS
     * daquele domínio. Fallback EXPLÍCITO e contabilizado — a R31 o substituiu,
     * no caminho do marcador, por `note_proven` (prefixo). Reserva não emitida
     * não é "concluída" por uma fila ociosa: ela nunca entrou na fila.
     */
    uint64_t note_queue_idle(uint64_t queue) {
        std::lock_guard<std::mutex> lk(m_);
        uint64_t n = 0;
        for (auto & u : units_) {
            if (u.queue == queue && u.state == State::SUBMITTED) {
                u.state = State::DONE;
                stats_.completed++;
                n++;
            }
        }
        if (n != 0) {
            cv_.notify_all();
        }
        return n;
    }

    /** Unidades que OCUPAM slot (PENDING/SUBMITTED/FAILED/STALE), com o estado. */
    std::vector<Unit> pending_units() {
        std::lock_guard<std::mutex> lk(m_);
        std::vector<Unit> out;
        for (const auto & u : units_) {
            if (ocupa(u.state)) {
                out.push_back(u);
            }
        }
        return out;
    }

    /**
     * R27/K — inutiliza a época APENAS com a fila comprovadamente vazia.
     * Com trabalho em voo a chamada é RECUSADA (false): zerar contador não pode
     * apagar prova de trabalho pendente. O ID (seq_) permanece monotônico e as
     * estatísticas são conservadas como diagnóstico.
     */
    bool reset_epoch() {
        std::lock_guard<std::mutex> lk(m_);
        if (poisoned_) {
            return false;   // R31/B2 — incerteza não é apagada por reset
        }
        if (pending_locked_all() != 0 || any_pending_locked()) {
            return false;
        }
        units_.clear();
        epoch_++;
        stats_.epochs++;
        return true;
    }

    /** Compatibilidade: reset só é aceito com a fila vazia. */
    bool reset() { return reset_epoch(); }

private:
    static int64_t now_us() {
        return std::chrono::duration_cast<std::chrono::microseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
    }

    Unit * find_locked(uint64_t id, uint64_t epoch) {
        for (auto & u : units_) {
            if (u.submit_id == id && u.epoch == epoch) {
                return &u;
            }
        }
        return nullptr;
    }

    uint64_t note_locked(uint64_t queue, Resource resource, uint64_t handle,
                         uint64_t value, uint64_t graph_gen, uint64_t device,
                         const Ident & ident) {
        Unit u;
        u.submit_id = ++seq_;
        u.epoch     = epoch_;
        u.device    = device;
        u.queue     = queue;
        u.resource  = resource;
        u.fence     = handle;
        u.fence_gen = 0;        // nada emitido: o valor chega no commit
        u.planned   = value;
        u.graph_gen = graph_gen;
        u.ident     = ident;
        u.state     = State::PENDING;
        units_.push_back(u);
        stats_.submits++;
        // R30/A4 - pico de pendentes no PERCURSO COMUM (a R29 so atualizava em
        // note_submit_as, e o pico saia zero com centenas de envios).
        const uint64_t p = pending_locked(queue);
        if (p > stats_.max_pending) {
            stats_.max_pending = p;
        }
        return u.submit_id;
    }

    // Slot OCUPADO = trabalho possivelmente em voo (ou em quarentena).
    // Só DONE libera; ABANDONED nunca foi emitida, então não ocupa.
    static bool ocupa(State s) { return s != State::DONE && s != State::ABANDONED; }

    /**
     * Conclui SOMENTE o que a prova da unidade `provada` cobre:
     *  - TIMELINE: mesmo semáforo e valor <= valor comprovado (prefixo monotônico);
     *  - FENCE: mesmo handle e MESMO valor (geração); fence distinta nunca é
     *    concluída por prova alheia.
     * Reservas PENDING nunca são cobertas: não têm valor emitido.
     */
    void complete_covered_locked(const Unit & provada) {
        for (auto & u : units_) {
            if (u.state != State::SUBMITTED) {
                continue;
            }
            if (u.queue != provada.queue || u.resource != provada.resource ||
                u.fence != provada.fence) {
                continue;
            }
            const bool coberto = (u.submit_id == provada.submit_id) ||
                                 ((provada.resource == Resource::TIMELINE)
                                      ? (u.fence_gen <= provada.fence_gen)
                                      : (u.fence_gen == provada.fence_gen));
            if (coberto) {
                u.state = State::DONE;
                stats_.completed++;
            }
        }
    }

    uint64_t pending_locked(uint64_t queue) const {
        uint64_t n = 0;
        for (const auto & u : units_) {
            if (u.queue == queue && ocupa(u.state)) {
                n++;
            }
        }
        return n;
    }

    uint64_t pending_locked_all() const {
        uint64_t n = 0;
        for (const auto & u : units_) {
            if (ocupa(u.state)) {
                n++;
            }
        }
        return n;
    }

    bool any_pending_locked() const {
        for (const auto & u : units_) {
            if (u.state == State::PENDING || u.state == State::SUBMITTED) {
                return true;
            }
        }
        return false;
    }

    /** Unidade mais antiga que OCUPA slot (PENDING/SUBMITTED/FAILED/STALE). */
    Unit * oldest_occupying_locked(uint64_t queue) {
        for (auto & u : units_) {
            if (u.queue == queue && ocupa(u.state)) {
                return &u;
            }
        }
        return nullptr;
    }

    std::mutex m_;
    std::condition_variable cv_;
    std::vector<Unit> units_;
    Stats stats_{};
    uint64_t seq_ = 0;
    uint64_t epoch_ = 1;
    uint64_t dom_device_ = 0;
    uint64_t dom_queue_ = 0;
    bool dom_configurado_ = false;
    bool poisoned_ = false;
    std::string poison_reason_;
};

inline Ledger & global() {
    static Ledger l;
    return l;
}

/** FNV-1a: identidade de owner como inteiro (0 = vazio/não vinculado). */
inline uint64_t owner_hash(const char * s) {
    if (s == nullptr || s[0] == '\0') {
        return 0;
    }
    uint64_t h = 1469598103934665603ull;
    for (const unsigned char * p = (const unsigned char *) s; *p != 0; ++p) {
        h ^= (uint64_t) *p;
        h *= 1099511628211ull;
    }
    return h != 0 ? h : 1;
}

inline const char * admit_name(Admit a) {
    switch (a) {
        case Admit::OK:           return "OK";
        case Admit::TIMEOUT:      return "TIMEOUT";
        case Admit::ERROR:        return "ERROR";
        case Admit::STALE:        return "STALE";
        case Admit::INCONSISTENT: return "INCONSISTENT";
        case Admit::CANCELLED:    return "CANCELLED";
        case Admit::UNSUPPORTED_DOMAIN: return "UNSUPPORTED_DOMAIN";
        case Admit::QUARANTINE:   return "QUARANTINE";
    }
    return "?";
}

inline const char * state_name(State s) {
    switch (s) {
        case State::PENDING: return "PENDING";
        case State::SUBMITTED: return "SUBMITTED";
        case State::ABANDONED: return "ABANDONED";
        case State::DONE:    return "DONE";
        case State::FAILED:  return "FAILED";
        case State::STALE:   return "STALE";
    }
    return "?";
}

} // namespace sig_vk_ledger
