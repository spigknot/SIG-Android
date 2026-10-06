// loader_cache_test.cpp — teste host (RED/GREEN) do cache de simbolos do
// android-opencl-loader: conta quantas vezes dlsym() e' chamado.
//   RED  (loader ORIG)   : dlsym a CADA chamada de funcao CL (comportamento antigo)
//   GREEN(loader PATCHED): 1 dlsym por simbolo por processo (cache)
// Compilado duas vezes pelo runner, com LOADER_FILE apontando para cada fixture.
// Uso: ./run_loader_cache_test.sh   (exit != 0 = falha)
#include <cstdio>
#include <cstring>
#include <string>
#include <CL/cl.h>   // tipos CL antes dos mocks/stub

// --- mocks de dlfcn (usados via fake_dlfcn/dlfcn.h) ---
static int g_dlsym_calls = 0;

extern "C" cl_int stub_any_fn(void) { return 0; }   // stub CHAMAVEL (retorna sucesso)

void * mock_dlopen(const char * name, int flags) {
    (void)flags;
    // primeira candidata do loader e' "libOpenCL.so" — sucesso nela
    if (name && strstr(name, "libOpenCL.so") != nullptr) return (void *)0x1234;
    return nullptr;
}
void * mock_dlsym(void * handle, const char * name) {
    (void)handle; (void)name;
    g_dlsym_calls++;
    return (void *)&stub_any_fn;  // ponteiro para stub real (pode ser chamado)
}
char * mock_dlerror(void) { return nullptr; }
int    mock_dlclose(void * handle) { (void)handle; return 0; }

// --- globais do modo host (usados pelo loader PATCHED; inofensivos p/ ORIG) ---
extern "C" {
int g_sig_loader_test_key = 1;      // cache ligada
int g_sig_loader_test_clcount = 0;  // contadores desligados (foco: contagem dlsym)
}

// --- loader sob teste (ORIG ou PATCHED conforme -DLOADER_FILE) ---
#include LOADER_FILE

#ifndef EXPECT_DLSYM
#error "defina -DEXPECT_DLSYM=<n>"
#endif

int main() {
    // 2 chamadas de clSetKernelArg + 2 de clEnqueueNDRangeKernel
    cl_command_queue q = (cl_command_queue)0x1;
    cl_kernel k = (cl_kernel)0x2;
    cl_int e1 = clSetKernelArg(k, 0, sizeof(cl_mem), nullptr);
    cl_int e2 = clSetKernelArg(k, 1, sizeof(cl_uint), nullptr);
    cl_int e3 = clEnqueueNDRangeKernel(q, k, 1, nullptr, nullptr, nullptr, 0, nullptr, nullptr);
    cl_int e4 = clEnqueueNDRangeKernel(q, k, 1, nullptr, nullptr, nullptr, 0, nullptr, nullptr);
    (void)e1; (void)e2; (void)e3; (void)e4;

    printf("dlsym calls = %d (esperado %d) | rc=%d%d%d%d\n",
           g_dlsym_calls, EXPECT_DLSYM, e1 != 0, e2 != 0, e3 != 0, e4 != 0);
    if (g_dlsym_calls != EXPECT_DLSYM) {
        printf("FALHA: contagem de dlsym diverge do esperado\n");
        return 1;
    }
    printf("OK\n");
    return 0;
}
