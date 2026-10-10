
#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include "D:/Projetos/SIG/docs/npu-hymt2/smart-rodada35/flow_seam.h"
static const char* current_env="1";
static bool mutant=false;
static const char* fixture_getenv(const char*) { return current_env; }
static int fixture_setenv(const char*,const char* v,int) { current_env=std::strcmp(v,"3")==0 ? "3":"1"; return 0; }
static int fixture_unsetenv(const char*) { current_env=nullptr; return 0; }
static int getter() { return mutant ? std::atoi(current_env):1; }
using ggml_backend_reg_t=void*;
static ggml_backend_reg_t ggml_backend_dev_backend_reg(void*) { return (void*)1; }
static void* ggml_backend_reg_get_proc_address(void*,const char*) { return (void*)&getter; }
static void rep(const char* fmt,...) { va_list args; va_start(args,fmt);std::vprintf(fmt,args);va_end(args);std::puts(""); }
#define getenv fixture_getenv
#define setenv fixture_setenv
#define unsetenv fixture_unsetenv
int main(int argc,char**) {
 mutant=argc>1; unsigned ops_retained=(unsigned)getter();
 void* dev_h=nullptr;struct{const char* nm;}ty={"q4_K"};
                            {
                                const char * pre = getenv("GGML_HEXAGON_OPSTAGE");
                                setenv("GGML_HEXAGON_OPSTAGE", "3", 1);
                                unsigned re_retained = ops_retained; int re_known = 0;
                                ggml_backend_reg_t r2 = ggml_backend_dev_backend_reg(dev_h);
                                if (r2) {
                                    void * f2 = ggml_backend_reg_get_proc_address(r2, "ggml_hexagon_get_opstage_effective");
                                    if (f2) { re_retained = (unsigned)((int (*)(void)) f2)(); re_known = 1; }
                                }
                                if (pre) setenv("GGML_HEXAGON_OPSTAGE", pre, 1); else unsetenv("GGML_HEXAGON_OPSTAGE");
                                rep("MM[%s] TEMPORAL: env-pos-init=3 getter_reconsulta=%u (known=%d!) => %s",
                                    ty.nm, re_retained, re_known,
                                    (re_known && re_retained == ops_retained) ? "RETIDO-CONFIRMADO (nao getenv!)" : "DIVERGIU (mutante?)");
                            }

 const bool go=flow_opstage_preflight(ops_retained);
 std::printf("GATE_USES_FIRST_READ=%u permission=%d ENV_RESTORED=%s\n",ops_retained,(int)go,current_env);
 return 0;
}
