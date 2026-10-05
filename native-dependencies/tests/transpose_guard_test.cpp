// Teste nativo do guard F3b (transposes non-blocking) — host, mock de CL.
// Camadas: (1) MOCK da consulta clGetCommandQueueInfo (props/erro/contagem);
// (2) design atual (consulta por chamada, sem cache) = GREEN; (3) copia do
// design ANTIGO (cache unico de primeiro-queue) = RED documentado.
// Build/run: ./run_transpose_guard_test.sh (exit != 0 = falha).
#define SIG_TRANSPOSE_GUARD_TEST 1
#include <cstdio>

// --- shim CL minimo (host) ---
typedef struct _cl_command_queue * cl_command_queue;
typedef int cl_int;
typedef unsigned int cl_command_queue_properties;
typedef unsigned int cl_command_queue_info;
#define CL_SUCCESS 0
#define CL_INVALID_COMMAND_QUEUE (-36)
#define CL_QUEUE_PROPERTIES 0x1090u
#define CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE (1u << 1)

#include "sig_transpose_guard.h"

// --- mock controlavel ---
static cl_command_queue_properties g_props = 0;
static cl_int g_rc = CL_SUCCESS;
static int g_calls = 0;
static cl_int mock_query(cl_command_queue q, cl_command_queue_info info,
                         size_t sz, void * val, size_t * ret) {
    (void)q; (void)info; (void)ret;
    g_calls++;
    if (g_rc != CL_SUCCESS) return g_rc;
    if (sz >= sizeof(cl_command_queue_properties)) {
        *(cl_command_queue_properties *)val = g_props;
    }
    return CL_SUCCESS;
}
static bool guard(cl_command_queue q) { return sig_transpose_guard_impl(q, mock_query); }

static int g_fail = 0;
#define CHECK(nome, cond) do { \
    printf("  %-58s %s\n", nome, (cond) ? "PASS" : "FAIL"); \
    if (!(cond)) g_fail++; \
} while (0)

int main() {
    printf("== guard F3b — teste nativo (host, mock CL) ==\n");
    cl_command_queue A = (cl_command_queue)0x1000;
    cl_command_queue B = (cl_command_queue)0x2000;
    cl_command_queue C = (cl_command_queue)0x3000;

    printf("GREEN (design atual: consulta por chamada, sem cache):\n");
    g_props = 0; g_rc = CL_SUCCESS; g_calls = 0;
    CHECK("in-order (props=0) -> fast", guard(A) == true);
    g_props = CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE;
    CHECK("out-of-order -> blocking", guard(B) == false);
    g_rc = CL_INVALID_COMMAND_QUEUE;
    CHECK("erro na consulta -> blocking (fail-safe)", guard(C) == false);
    g_rc = CL_SUCCESS; g_props = 0;
    CHECK("volta a fila in-order -> fast (sem estado velho)", guard(A) == true);
    CHECK("consulta executada por chamada (>= 4)", g_calls >= 4);
    printf("  consultas ao driver: %d\n", g_calls);

    printf("RED (design ANTIGO: cache unico de primeiro-queue — copia p/ evidencia):\n");
    {
        int old_cache = -1;
        auto old_guard = [&](cl_command_queue q) {
            if (old_cache < 0) {
                cl_command_queue_properties props = 0;
                cl_int rc = mock_query(q, CL_QUEUE_PROPERTIES, sizeof(props), &props, NULL);
                old_cache = (rc == CL_SUCCESS)
                                ? ((props & CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE) ? 1 : 0)
                                : 0;
            }
            return old_cache == 0;
        };
        g_rc = CL_SUCCESS; g_props = 0;
        bool a = old_guard(A);
        g_props = CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE;
        bool b = old_guard(B);
        printf("  [antigo] A in-order->fast=%d | B ooo->fast=%d\n", a, b);
        CHECK("RED: design antigo devolve fast p/ fila OOO (bug demonstrado)", a == true && b == true);
    }

    printf("\nresultado: %s (%d falhas)\n", g_fail ? "FAIL" : "PASS", g_fail);
    return g_fail ? 1 : 0;
}
