// loader_hardening_test.cpp — contratos de endurecimento do cache do loader
// (SOMENTE contra o loader PATCHED endurecido; host; sem device).
// Cobre: falha TRANSITORIA nao envenena; simbolo ausente = null seguro com
// RETRY a cada chamada (falha nao cacheada); concorrencia (publicacao atomica,
// sem ponteiro parcial); chave OFF = 1 dlsym por chamada; contadores reais.
#include <cstdio>
#include <cstring>
#include <string>
#include <atomic>
#include <thread>
#include <vector>

#include <CL/cl.h>

// ---- estado do mock controlavel pelo teste ----
static std::atomic<int> g_dlsym_calls{0};
static std::atomic<int> g_fail_next{0};      // falha as proximas N chamadas de dlsym
static std::atomic<int> g_always_fail{0};    // 1 = dlsym sempre null

void * mock_dlopen(const char * name, int flags) {
    (void)flags;
    if (name && strstr(name, "libOpenCL.so") != nullptr) return (void *)0x1234;
    return nullptr;
}
extern "C" cl_int stub_any_fn(void) { return 0; }
void * mock_dlsym(void * handle, const char * name) {
    (void)handle; (void)name;
    g_dlsym_calls.fetch_add(1);
    if (g_always_fail.load()) return nullptr;
    if (g_fail_next.load() > 0) { g_fail_next.fetch_sub(1); return nullptr; }
    return (void *)&stub_any_fn;
}
char * mock_dlerror(void) { return nullptr; }
int    mock_dlclose(void * handle) { (void)handle; return 0; }

// ---- definicoes esperadas pelo loader sob SIG_LOADER_HOST_TEST ----
extern "C" {
int g_sig_loader_test_key = 1;      // cache LIGADA
int g_sig_loader_test_clcount = 1;  // contadores LIGADOS
}

#include "loader_PATCHED.cpp"

static int fails = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("  FAIL: %s\n", msg); fails++; } else { printf("  ok: %s\n", msg); } } while (0)

int main() {
    printf("== A) chave ON: 3 chamadas -> 1 dlsym; contadores shim=3 dlsym=1 ==\n");
    sig_opencl_counters_reset();
    cl_kernel k = (cl_kernel)0x2;
    for (int i = 0; i < 3; ++i) (void)clSetKernelArg(k, 0, sizeof(cl_mem), nullptr);
    CHECK(sig_opencl_shim_calls() == 3, "shim conta TODAS as chamadas (3)");
    CHECK(sig_opencl_dlsym_calls() == 1, "dlsym real = 1 (cache por simbolo)");

    printf("== B) falha TRANSITORIA antes do load nao envenena (retry) ==\n");
    sig_opencl_counters_reset();
    g_fail_next.store(1);
    g_always_fail.store(0);
    // simbolo novo (clGetKernelInfo ainda nao resolvido): 1a chamada falha,
    // 2a resolve, 3a cacheia
    cl_int r1 = clGetKernelInfo(k, (cl_kernel_info)0, 0, nullptr, nullptr);
    cl_int r2 = clGetKernelInfo(k, (cl_kernel_info)0, 0, nullptr, nullptr);
    cl_int r3 = clGetKernelInfo(k, (cl_kernel_info)0, 0, nullptr, nullptr);
    CHECK(r1 != 0, "1a chamada falha-transitoria retorna erro (sem crash)");
    CHECK(r2 == 0 && r3 == 0, "2a/3a resolvem e passam");
    CHECK(sig_opencl_dlsym_calls() == 2, "dlsym = 2 (falha NAO cacheada + retry)");

    printf("== C) simbolo AUSENTE: null seguro, SEM cache de falha (retry por chamada) ==\n");
    sig_opencl_counters_reset();
    g_always_fail.store(1);
    cl_int e1 = clEnqueueNDRangeKernel((cl_command_queue)0x1, k, 1, nullptr, nullptr, nullptr, 0, nullptr, nullptr);
    cl_int e2 = clEnqueueNDRangeKernel((cl_command_queue)0x1, k, 1, nullptr, nullptr, nullptr, 0, nullptr, nullptr);
    CHECK(e1 != 0 && e2 != 0, "erro retornado nas duas (null-safe)");
    CHECK(sig_opencl_dlsym_calls() == 2, "dlsym tentou de novo na 2a (sem envenenamento)");
    g_always_fail.store(0);
    cl_int e3 = clEnqueueNDRangeKernel((cl_command_queue)0x1, k, 1, nullptr, nullptr, nullptr, 0, nullptr, nullptr);
    CHECK(e3 == 0, "driver 'disponivel' depois: resolve e passa");
    CHECK(sig_opencl_dlsym_calls() == 3, "dlsym = 3 (agora cacheado)");

    printf("== D) concorrencia: 8 threads x 200 chamadas num simbolo NOVO ==\n");
    sig_opencl_counters_reset();
    std::atomic<int> ok{0};
    std::vector<std::thread> ts;
    for (int t = 0; t < 8; ++t) ts.emplace_back([&]{ for (int i = 0; i < 200; ++i) if (clGetKernelWorkGroupInfo((cl_kernel)0x3, (cl_device_id)0x4, (cl_kernel_work_group_info)0, 0, nullptr, nullptr) == 0) ok.fetch_add(1); });
    for (auto & th : ts) th.join();
    CHECK(ok.load() == 1600, "todas as 1600 chamadas OK (sem ponteiro parcial)");
    CHECK(sig_opencl_shim_calls() == 1600, "shim = 1600");
    CHECK(sig_opencl_dlsym_calls() <= 8, "dlsym <= 8 (corrida dupla-benigna no maximo)");

    printf("== E) chave OFF: caminho original (dlsym por chamada) ==\n");
    g_sig_loader_test_key = 0;
    sig_opencl_counters_reset();
    (void)clGetPlatformIDs(0, nullptr, nullptr);
    (void)clGetPlatformIDs(0, nullptr, nullptr);
    CHECK(sig_opencl_dlsym_calls() == 2, "cache OFF => 2 chamadas = 2 dlsym (comportamento original)");
    g_sig_loader_test_key = 1;

    printf("== F) chave ON de novo: cache volta a valer ==\n");
    sig_opencl_counters_reset();
    (void)clGetPlatformIDs(0, nullptr, nullptr);   // 1o com cache ON: resolve (a fase E nao cacheou)
    (void)clGetPlatformIDs(0, nullptr, nullptr);   // 2o: hit
    CHECK(sig_opencl_dlsym_calls() == 1, "resolucao unica ao voltar ON; 2a chamada = hit");

    printf(fails == 0 ? "RESULTADO: PASS (A-F)\n" : "RESULTADO: FAIL (%d)\n", fails);
    return fails == 0 ? 0 : 1;
}
