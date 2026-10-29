// R24/REQ — OPÇÕES DA TENTATIVA (fotografia imutável), não canal global.
//
// Defeito que isto corrige: `g_audio_ctx_window_samples` era um global one-shot
// consumido por QUALQUER job que transcrevesse em seguida (`.exchange(-1)`),
// e `SIG_VK_SUBMIT_MB`/K/serial vinham de getenv do processo. Duas tentativas
// concorrentes podiam trocar de configuração entre si.
//
// Contrato:
//   * a fotografia é publicada UMA vez por tentativa (owner + attempt);
//   * o consumo é por owner/attempt e é único (take);
//   * publicar de novo com o MESMO owner/attempt e valores diferentes é
//     RECUSADO (não sobrescreve silenciosamente a tentativa em curso);
//   * `drop(owner, attempt)` limpa em erro/cancelamento;
//   * tentativa antiga não consome nem apaga a nova.
#ifndef SIG_ATTEMPT_OPTS_H
#define SIG_ATTEMPT_OPTS_H

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>

namespace sig_attempt {

enum { SLOTS = 4 };

struct Opts {
    int64_t owner     = 0;
    int64_t attempt   = 0;
    int64_t window    = 0;   // amostras de janela (0 = desligada / default)
    int     submit_mb = 0;   // 0 = DEFAULT
    int     k         = 0;   // 0 = sem limite
    int     serial    = 0;   // 0/1
    bool    usado     = false;

    bool mesmo_que(const Opts & o) const {
        return owner == o.owner && attempt == o.attempt && window == o.window &&
               submit_mb == o.submit_mb && k == o.k && serial == o.serial;
    }
};

enum class Publish { OK, DUPLICADO, CONFLITO, SEM_ESPACO, INVALIDO };

struct Table {
    Opts slots[SLOTS];

    /** Publica uma tentativa. Nunca sobrescreve conteúdo diferente do mesmo dono. */
    Publish publish(const Opts & o) {
        if (o.owner <= 0 || o.attempt <= 0) {
            return Publish::INVALIDO;
        }
        std::lock_guard<std::mutex> lk(m_);
        for (int i = 0; i < SLOTS; ++i) {
            if (slots[i].owner == o.owner && slots[i].attempt == o.attempt) {
                return slots[i].mesmo_que(o) ? Publish::DUPLICADO : Publish::CONFLITO;
            }
        }
        for (int i = 0; i < SLOTS; ++i) {
            if (slots[i].owner == 0) {
                slots[i] = o;
                return Publish::OK;
            }
        }
        return Publish::SEM_ESPACO;
    }

    /** Consome a fotografia da tentativa (única). `attempt <= 0` casa pelo dono
     *  (o caminho JNI owned não carrega o número da tentativa). */
    bool take(int64_t owner, int64_t attempt, Opts * out) {
        std::lock_guard<std::mutex> lk(m_);
        for (int i = 0; i < SLOTS; ++i) {
            const bool casa = slots[i].owner == owner &&
                              (attempt <= 0 || slots[i].attempt == attempt);
            if (casa) {
                if (out != nullptr) {
                    *out = slots[i];
                }
                slots[i] = Opts{};
                return true;
            }
        }
        return false;
    }

    /** Limpa em erro/cancelamento. Tentativa antiga não apaga a nova. */
    bool drop(int64_t owner, int64_t attempt) {
        std::lock_guard<std::mutex> lk(m_);
        for (int i = 0; i < SLOTS; ++i) {
            const bool casa = slots[i].owner == owner &&
                              (attempt <= 0 || slots[i].attempt == attempt);
            if (casa) {
                slots[i] = Opts{};
                return true;
            }
        }
        return false;
    }

    int pendentes() {
        std::lock_guard<std::mutex> lk(m_);
        int n = 0;
        for (int i = 0; i < SLOTS; ++i) {
            if (slots[i].owner != 0) {
                ++n;
            }
        }
        return n;
    }

private:
    std::mutex m_;
};

// Instância única do processo (o JNI usa esta).
inline Table & global() {
    static Table t;
    return t;
}

} // namespace sig_attempt

#endif // SIG_ATTEMPT_OPTS_H
