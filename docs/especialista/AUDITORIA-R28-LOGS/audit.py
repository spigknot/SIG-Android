from pathlib import Path
import subprocess, re, hashlib, json, os
OUT = Path(__file__).resolve().parent
OUT.mkdir(exist_ok=True)
ROOT = Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada28')
BASE = Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
PIN = '1ec81880944a63bc4aaf1abfe9a6d35c7569a757'
def git_file(path):
    return subprocess.run(['git', '-C', 'C:/llama-npu/llama.cpp', 'show', PIN+':'+path], capture_output=True, text=True, check=True).stdout
def extract(text, name):
    m = re.search(r'^static[^\n]*\b'+re.escape(name)+r'\([^\n]*\)\s*\{', text, re.M)
    if not m:
        raise ValueError('Definition missing: '+name)
    start = m.start(); i = text.index('{', start); depth = 1; i += 1
    while depth:
        depth += (text[i] == '{') - (text[i] == '}'); i += 1
    return text[start:i]
fixture = (ROOT/'r28_fixture.c').read_text()
pin_host = git_file('ggml/src/ggml-hexagon/ggml-hexagon.cpp')
pin_quants = git_file('ggml/src/ggml-quants.c')
wrong = extract(fixture, 'get_scale_min_k4')
right = re.sub(r'^\s*\*m =[^\n]+$', lambda m: m.group(0).replace('q[j-4]', 'q[j]'), wrong, flags=re.M)
assert wrong != right
pack = extract(pin_host, 'repack_q4_K_tiled')
reader = extract(fixture, 'leitor_q4k_w')
canonical = extract(pin_quants, 'get_scale_min_k4')
print('PIN_CANONICAL_HELPER:', '\n'.join(l.strip() for l in canonical.splitlines() if '*m =' in l))
print('PIN_PACK_SHA256',hashlib.sha256(pack.encode()).hexdigest())
pre = '''#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <stdarg.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
#define HTP_MM_WEIGHT_TILE_SIZE_Q4_1 640
size_t ggml_row_size(enum ggml_type type, int64_t n) {
 if(type==GGML_TYPE_Q4_K) return (size_t)(n/256)*sizeof(block_q4_K);
 if(type==GGML_TYPE_Q6_K) return (size_t)(n/256)*sizeof(block_q6_K);
 return 0;
}
size_t ggml_type_size(enum ggml_type type) {
 if(type==GGML_TYPE_Q4_K) return sizeof(block_q4_K);
 if(type==GGML_TYPE_Q6_K) return sizeof(block_q6_K);
 return 0;
}
const char *ggml_type_name(enum ggml_type type) { (void)type; return "audit"; }
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x+n-1)/n)*n; }
void ggml_abort(const char *file, int line, const char *fmt, ...) {
  va_list ap; va_start(ap,fmt); fprintf(stderr,"ABORT %s:%d ",file,line); vfprintf(stderr,fmt,ap); va_end(ap); abort();
}
'''
main = '''
int main(void) {
 const int K=256,N=64; float src[K*N],dc[K*N]; block_q4_K q[N];
 for(int i=0;i<K*N;i++) src[i]=(float)(sin((double)i*0.017)*1.7);
 quantize_q4_K(src,q,N,K,NULL);
 for(int r=0;r<N;r++) dequantize_row_q4_K(q+r,dc+(size_t)r*K,K);
 uint8_t tiled[10240]; memset(tiled,0,sizeof(tiled));
 struct ggml_tensor t; memset(&t,0,sizeof(t)); t.ne[0]=K;t.ne[1]=N;t.ne[2]=1;t.ne[3]=1;t.data=tiled;t.type=GGML_TYPE_Q4_K;
 repack_q4_K_tiled(&t,q,0,sizeof(q));
 long bad=0,nf=0,bad_low=0,bad_high=0; double maxabs=0;
 for(int r=0;r<N;r++) for(int k=0;k<K;k++) {
   float w=leitor_q4k_w(tiled,K/32,r,k), ref=dc[r*K+k];
   if(!isfinite(w)||!isfinite(ref)){nf++;continue;}
   double d=fabs((double)w-ref);if(d>maxabs)maxabs=d;
   if(d>0.05){bad++;if(k<128)bad_low++;else bad_high++;}
 }
 printf("Q4K same_reader bad=%ld nonfinite=%ld max_abs=%.9f bad_k0_127=%ld bad_k128_255=%ld\\n",bad,nf,maxabs,bad_low,bad_high);
 return bad||nf?1:0;
}
'''
inc = ['-I'+(BASE/'include').as_posix(), '-I'+(BASE/'src').as_posix()]
obj=OUT/'quants.o'
env=os.environ.copy(); env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
cmd=['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*inc,'-c',(BASE/'src/ggml-quants.c').as_posix(),'-o',obj.as_posix()]
r=subprocess.run(cmd,capture_output=True,text=True,env=env); (OUT/'quants-build.log').write_text(r.stdout+r.stderr)
assert r.returncode == 0, r.stderr[-2000:]
records=[]
for name,helper in [('wrong_helper',wrong),('correct_helper',right)]:
    source=OUT/(name+'.cpp'); source.write_text(pre+helper+'\n'+pack+'\n'+reader+'\n'+main)
    exe=OUT/(name+'.exe')
    cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-ffunction-sections','-fdata-sections',*inc,source.as_posix(),obj.as_posix(),'-Wl,--gc-sections','-o',exe.as_posix()]
    r=subprocess.run(cmd,capture_output=True,text=True,env=env); (OUT/(name+'-build.log')).write_text(r.stdout+r.stderr)
    print(name,'BUILD_EXIT',r.returncode)
    if r.returncode: raise RuntimeError(r.stderr[-2000:])
    r=subprocess.run([exe.as_posix()],capture_output=True,text=True,env=env)
    (OUT/(name+'.log')).write_text(r.stdout+r.stderr)
    print(name,'RUN_EXIT',r.returncode,r.stdout.strip())
    records.append({'name':name,'exit':r.returncode,'stdout':r.stdout,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
assert records[0]['exit']==1 and 'bad=7968 ' in records[0]['stdout']
assert records[1]['exit']==0 and 'bad=0 ' in records[1]['stdout']
(OUT/'results.json').write_text(json.dumps(records,indent=2))
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f, len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('EVIDENCE_HASHES',len(checks),'MATCH',sum(ok for _,ok in checks))
print('MISSING_GENERATED', [f for f in ['r28_cores_gen.h','r28_switch_gen.inc'] if not (ROOT/f).is_file()])
print('NOTE: pinned real pack and unchanged fixture reader; only helper m-index changed. Independent local canonical implementation, not executor build provenance.')
