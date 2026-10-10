from pathlib import Path
import hashlib,re,json,subprocess,os
R=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada38');O=Path(__file__).resolve().parent
src=(R/'probe-r38-com-temporal.cpp').read_text();log=(R/'r38-capture-full.txt').read_text()
checks=[]
for h,f in re.findall(r'(?m)^([0-9a-f]{64})\s+(\S+)',(R/'MANIFESTO-SHA256.txt').read_text()):
 if not f.startswith('relatorio:'):checks.append({'file':f,'match':hashlib.sha256((R/f).read_bytes()).hexdigest()==h})
assert len(checks)==3 and all(x['match'] for x in checks)
a=src.index('                            {\n                                const char * pre = getenv("GGML_HEXAGON_OPSTAGE");')
b=src.index('                            if (!flow_opstage_preflight(ops_retained))',a)
block=src[a:b]
# Exact diagnostic block. Only adapters for env and registry, no real backend init.
# Stable env strings exclude the separate getenv-pointer lifetime hazard.
head=r'''
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
'''
foot=r'''
 const bool go=flow_opstage_preflight(ops_retained);
 std::printf("GATE_USES_FIRST_READ=%u permission=%d ENV_RESTORED=%s\n",ops_retained,(int)go,current_env);
 return 0;
}
'''
(O/'diagnostic-block.cpp').write_text(head+block+foot)
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
r=subprocess.run(['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2',(O/'diagnostic-block.cpp').as_posix(),'-o',(O/'diagnostic-block.exe').as_posix()],capture_output=True,text=True,env=env)
(O/'build.log').write_text(r.stdout+r.stderr);print('BUILD_EXIT',r.returncode);assert r.returncode==0,r.stderr[-1200:]
repro=[]
for name,args in [('baseline',[]),('getter_env_mutant',['mutant'])]:
 r=subprocess.run([(O/'diagnostic-block.exe').as_posix(),*args],capture_output=True,text=True,env=env)
 (O/(name+'.log')).write_text(r.stdout+r.stderr);print(name,'EXIT',r.returncode);print(r.stdout.strip())
 assert r.returncode==0 and 'permission=0' in r.stdout
 if args:assert 'DIVERGIU' in r.stdout
 repro.append({'scenario':name,'exit':r.returncode,'stdout':r.stdout})
# Do not dedupe identical temporal messages as distinct cases: source loops B1/2/128.
patterns={
 'metrics':r'NpuProbe\(\s*(\d+)\): (MM\[K=.*?\].*nmse_pin=.*)',
 'exec_fail':r'NpuProbe\(\s*(\d+)\): (MM\[.*?\].*EXEC_FAIL.*)',
 'totals':r'NpuProbe\(\s*(\d+)\): (MMTOTAL.*)'}
aggregated={k:list(dict.fromkeys(re.findall(p,log))) for k,p in patterns.items()}
assert len(aggregated['metrics'])==len(aggregated['exec_fail'])==3
print('HASH_MATCH',len(checks),'NORMAL_METRICS',len(aggregated['metrics']),'REFUSED_CASES',len(aggregated['exec_fail']))
print('SCOPE: offline fragment counterexample only; not Android mutated cohort or backend-init validation.')
(O/'results.json').write_text(json.dumps({'checks':checks,'reproduction':repro,'device_log_aggregation':aggregated,'scope':'exact temporal diagnostic block + frozen R35 predicate, mocked registry/env; no real backend or device execution'},ensure_ascii=False,indent=2))
