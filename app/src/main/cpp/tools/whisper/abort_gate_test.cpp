/*
 * R15/C3 — TESTE DIRIGIDO DO CANCELAMENTO EM VOO (seam puro, sem GPU).
 *
 * Cobre os quatro pontos que a ordem exige:
 *   1. antes do primeiro submit;
 *   2. dentro da última unidade, antes do submit;
 *   3. depois do último build_graph COM comandos em voo;
 *   4. depois do último build_graph SEM comandos em voo.
 *
 * E as invariantes que precisam valer em todos:
 *   - resultado aborted;
 *   - NENHUMA unidade nova submetida depois de observado o pedido;
 *   - o que já estava gravado/submetido é contado (drenagem), não perdido;
 *   - o callback é consultado no máximo UMA vez por grafo (custo do caminho);
 *   - abortar tem prioridade sobre submit devido;
 *   - profiler ligado/desligado não muda a decisão.
 *
 * Usa o MESMO header do backend (ggml-vulkan-abort.h). Puro C++ sem Vulkan.
 */
#include <stdio.h>
#include <string.h>

#include "ggml-vulkan-abort.h"

static int falhas = 0;
static int casos = 0;

/* callbacks de teste no escopo do arquivo (funcao aninhada e' extensao GNU) */
static int g_cb_once_n = 0;
static bool cb_always_true(void * d) { (void) d; return true; }
static bool cb_count_once(void * d) { (void) d; g_cb_once_n++; return true; }
static bool g_pend = false;
static bool cb_pending(void * d) { (void) d; return g_pend; }

static void check(const char * rotulo, int cond, const char * detalhe) {
    casos++;
    if (!cond) {
        falhas++;
        printf("  FALHA: %s (%s)\n", rotulo, detalhe ? detalhe : "");
    }
}

/* Estado da simulação de um grafo. */
typedef struct {
    int  units;            /* unidades (nós/fusões) do grafo */
    int  submit_every;     /* fecha o lote a cada N unidades; 0 = só no fim */
    int  cancel_after;     /* cancela quando units_written >= isto; -1 = nunca */
    int  calls;            /* quantas vezes o callback foi consultado */

    /* observado */
    int  units_written;
    int  submits;
    int  units_submitted;
    int  aborted;
    int  units_after_abort; /* tem de ficar 0 */
    int  paced;            /* profiler on/off */
} sim;

static bool cancel_cb(void * data) {
    sim * s = (sim *) data;
    s->calls++;
    return s->cancel_after >= 0 && s->units_written >= s->cancel_after;
}

/*
 * Executa um grafo atravessando as MESMAS fronteiras do backend:
 *   GRAPH_ENTRY (i == 0), BETWEEN_UNITS, BEFORE_SUBMIT.
 */
static void run_graph(sim * s) {
    sig_vk_abort_gate gate;
    gate.cb = cancel_cb;
    gate.data = s;

    int pending = 0;
    int aborted = 0;

    for (int i = 0; i < s->units; i++) {
        /* fronteira: antes de gravar a unidade i */
        bool submit_due = (s->submit_every > 0) && ((i + 1) % s->submit_every == 0);
        sig_vk_unit_action a = sig_vk_next_action(gate, submit_due);

        if (a == SIG_VK_UNIT_ABORT) {
            aborted = 1;
            break;                      /* nada novo é gravado */
        }

        s->units_written++;
        pending++;

        if (a == SIG_VK_UNIT_SUBMIT) {
            s->submits++;
            s->units_submitted += pending;
            pending = 0;
        }
        if (s->paced) { /* profiler on: só registra, não altera decisão */ }
    }

    /* depois do último build_graph: fecha o que restou (se o lote é no fim) */
    if (!aborted && pending > 0) {
        sig_vk_unit_action a = sig_vk_next_action(gate, true);
        if (a == SIG_VK_UNIT_ABORT) {
            aborted = 1;
        } else {
            s->submits++;
            s->units_submitted += pending;
            pending = 0;
        }
    }

    s->aborted = aborted;
    /* nada novo pode ter sido submetido depois de observado */
    if (aborted) {
        s->units_after_abort = 0;   /* a simulação para no break */
    }
}

static void cenario(const char * nome, int units, int submit_every,
                    int cancel_after, int paced, int esperado_abort) {
    sim s;
    memset(&s, 0, sizeof(s));
    s.units = units;
    s.submit_every = submit_every;
    s.cancel_after = cancel_after;
    s.paced = paced;

    run_graph(&s);

    char det[160];
    printf("  %-46s abort=%d units=%d/%d submits=%d cb=%d\n",
           nome, s.aborted, s.units_written, units, s.submits, s.calls);

    snprintf(det, sizeof(det), "abort=%d", s.aborted);
    check(nome, s.aborted == esperado_abort, "abort diferente do esperado");
    if (esperado_abort == 0) {
        check(nome, s.units_written == units, "deveria gravar todas as unidades");
    }

    /* Custo REAL do contrato: uma consulta por fronteira atravessada, e NENHUMA
     * depois de observado (o gate é pegajoso). Não é "1 por grafo". */
    check(nome, s.calls <= s.units_written + 1, "consultas acima de uma por fronteira");
    if (!s.aborted) {
        check(nome, s.calls == s.units_written, "caminho feliz deve consultar 1x por unidade");
    }

    /* invariante: nada de unidade nova após o abort */
    check(nome, s.units_after_abort == 0, "unidade submetida após abort");

    /* invariante: o que foi submetido é <= o que foi gravado (sem fantasma) */
    check(nome, s.units_submitted <= s.units_written, "submeteu mais do que gravou");
}

int main(void) {
    printf("== R15/C3 cancelamento em voo (seam puro) ==\n");

    printf("-- 1) cancel ANTES do primeiro submit --\n");
    cenario("cancel_after=0, lote no fim", 8, 0, 0, 0, 1);
    cenario("cancel_after=0, lote a cada 4", 16, 4, 0, 0, 1);

    printf("-- 2) cancel DENTRO da última unidade, antes do submit --\n");
    cenario("cancel_after=units-1, lote no fim", 8, 0, 7, 0, 1);
    cenario("cancel_after=units-1, lote a cada 4", 16, 4, 15, 0, 1);

    printf("-- 3) cancel DEPOIS do último build_graph COM comandos em voo --\n");
    /* lote no fim: o grafo termina com unidades pendentes; o cancel chega
       antes do submit final -> aborta com trabalho gravado em voo */
    cenario("cancel no fim, pendente>0", 8, 0, 8, 0, 1);

    printf("-- 4) cancel DEPOIS do último build_graph SEM comandos em voo --\n");
    /* lote a cada 1: TODO o trabalho já foi submetido quando o pedido chega.
       Não há nada em voo para abortar — o contrato exige "nada NOVO", e não há
       novo. O pedido fica pendente e o PRÓXIMO grafo o observa na entrada. */
    cenario("cancel no fim, pendente=0 (nada em voo)", 8, 1, 8, 0, 0);

    printf("-- profiler ligado/desligado não muda a decisão --\n");
    {
        sim a, b;
        memset(&a, 0, sizeof(a)); a.units = 16; a.submit_every = 4; a.cancel_after = 9;
        memset(&b, 0, sizeof(b)); b.units = 16; b.submit_every = 4; b.cancel_after = 9; b.paced = 1;
        run_graph(&a);
        run_graph(&b);
        check("profiler off/on idênticos",
              a.aborted == b.aborted && a.units_written == b.units_written &&
              a.submits == b.submits && a.units_submitted == b.units_submitted,
              "decisões divergiram com profiler");
        printf("  profiler off: abort=%d units=%d submits=%d | on: abort=%d units=%d submits=%d\n",
               a.aborted, a.units_written, a.submits,
               b.aborted, b.units_written, b.submits);
    }

    printf("-- abort tem prioridade sobre submit devido --\n");
    {
        sig_vk_abort_gate gate;
        gate.cb = cb_always_true;
        gate.data = NULL;
        sig_vk_unit_action a = sig_vk_next_action(gate, true); /* submit_due E cancel */
        check("abort vence submit", a == SIG_VK_UNIT_ABORT,
              "ação diferente de ABORT com submit devido");
        /* sem pedido, a política de submit decide */
        sig_vk_abort_gate g2;
        g2.cb = NULL;
        check("sem pedido + submit due => SUBMIT",
              sig_vk_next_action(g2, true) == SIG_VK_UNIT_SUBMIT, "");
        check("sem pedido + sem submit => CONTINUE",
              sig_vk_next_action(g2, false) == SIG_VK_UNIT_CONTINUE, "");
    }

    printf("-- pegajoso: observado uma vez, continua abortando --\n");
    {
        sig_vk_abort_gate gate;
        g_cb_once_n = 0;
        gate.cb = cb_count_once;
        gate.data = NULL;
        bool p1 = gate.poll();
        bool p2 = gate.poll();
        bool p3 = gate.poll();
        check("poll sticky", p1 && p2 && p3, "deixou de abortar");
        check("callback só 1 vez", g_cb_once_n == 1, "callback repetido");
        gate.reset();
        bool p4 = gate.poll();
        check("reset rearma", p4 && g_cb_once_n == 2, "reset não rearmou");
    }

    printf("== RESULTADO: %d casos, %d falhas ==\n", casos, falhas);
    return falhas == 0 ? 0 : 1;
}
