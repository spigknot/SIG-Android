from pathlib import Path
import subprocess,hashlib,re,json,os,math,csv,struct,shutil
R=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada41'); O=Path(__file__).resolve().parent; C=R.parent/'smart-rodada39/captura-q6-k6144'; G=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','');incs=['-I'+str(G/'include'),'-I'+str(G/'src')];checks=[]
for h,name in re.findall(r'(?m)^([0-9a-f]{64})\s+(\S+)',(R/'MANIFESTO-SHA256.txt').read_text()):
 p=R/name if not name.startswith('relatorio:') else R.parent.parent/'especialista'/name.split(':',1)[1]
 assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==h
 checks.append({'name':name,'size':p.stat().st_size,'sha256':h})
print('MANIFEST_OK',len(checks))
rows=list(csv.DictReader((R/'r41-referencias-completas.csv').open()));assert len(rows)==2048 and {int(x['n']) for x in rows}==set(range(2048))
rows.sort(key=lambda x:int(x['n'])); vals={k:[float(x[k]) for x in rows] for k in ['cpu','htp','rscalar','rtiled']};assert all(math.isfinite(x) for a in vals.values() for x in a)
# Independent references already generated over the same R39 bytes.
a=Path('D:/Projetos/SIG/docs/especialista/AUDITORIA-R39-LOGS');exact={}
for key,name in [('rscalar','rscalar.f64'),('rtiled','rtiled.f64')]:exact[key]=[x[0] for x in struct.iter_unpack('<d',(a/name).read_bytes())]
for key,name in [('cpu','cpuout_32768.bin'),('htp','htpout_32768.bin')]:exact[key]=[x[0] for x in struct.iter_unpack('<f',(C/name).read_bytes())]
diffs={k:max(abs(x-y) for x,y in zip(vals[k],exact[k])) for k in vals};assert all(x<5.01e-7 for x in diffs.values());metrics={}
for out,ref in [('cpu','rscalar'),('cpu','rtiled'),('htp','rscalar'),('htp','rtiled')]:
 er=[abs(x-y) for x,y in zip(exact[out],exact[ref])]; den=sum(y*y for y in exact[ref]);metrics[out+'_'+ref]={'nmse':sum(x*x for x in er)/den,'max_abs':max(er),'viol':sum(e>.02+.05*abs(y) for e,y in zip(er,exact[ref]))}
print('CSV_ROWS',len(rows),'MAX_ROUNDING_DIFF',diffs);print('METRICS',json.dumps(metrics))
top=sorted(range(2048),key=lambda n:abs(exact['htp'][n]-exact['rtiled'][n]),reverse=True)[:10];pct=[100*abs(exact['htp'][n]-exact['rtiled'][n])/abs(exact['rtiled'][n]) for n in top]
top_info={'indices':top,'relative_percent_min':min(pct),'relative_percent_max':max(pct),'legacy_violators_in_top10':sum(abs(exact['htp'][n]-exact['rtiled'][n])>.02+.05*abs(exact['rtiled'][n]) for n in top),'one_over_127_percent':100/127};print('TOP10_CHECK',json.dumps(top_info))
# Compile the delivered C loader unchanged. GCC C11 avoids permissive C++ conversion warnings.
quants=O/'quants.o';exe=O/'r41_refs.exe';commands=[['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*incs,'-c',str(G/'src/ggml-quants.c'),'-o',str(quants)],['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*incs,str(R/'r41_refs.c'),str(quants),'-Wl,--gc-sections','-o',str(exe)]]
for i,cmd in enumerate(commands):
 p=subprocess.run(cmd,capture_output=True,text=True,env=env);(O/f'build{i}.log').write_text(p.stdout+p.stderr);print('BUILD',i,'RC',p.returncode);assert p.returncode==0,p.stderr[-1000:]
names=['canonweights_9216.bin','actfloat_131072.bin','cpuout_32768.bin','htpout_32768.bin','tiled_raw.bin'];cases=[]
for label in ['original_r39','wrong_meta','htp_nan','zero_act','extra_byte']:
 d=O/'cases'/label;d.mkdir(parents=True,exist_ok=True)
 for name in names:shutil.copyfile(C/name,d/name)
 if label=='original_r39':shutil.copyfile(C/'meta.txt',d/'meta.txt')
 else:(d/'meta.txt').write_text('attempt=wrong K=256 N=64 B=128 type=q4_K pid=999\n')
 if label=='htp_nan':
  with (d/'htpout_32768.bin').open('r+b') as f:f.write(struct.pack('<f',math.nan))
 if label=='zero_act':(d/'actfloat_131072.bin').write_bytes(bytes(24576))
 if label=='extra_byte':
  with (d/'actfloat_131072.bin').open('ab') as f:f.write(b'X')
 p=subprocess.run([str(exe),str(d)],capture_output=True,text=True,env=env);(O/f'{label}.out').write_text(p.stdout);(O/f'{label}.err').write_text(p.stderr)
 item={'case':label,'exit':p.returncode,'valid_claim':'observacao numerica VALIDA' in p.stdout,'nd_nf':re.findall(r'nd=\d+ nf=\d+',p.stdout),'pin_line':next((l for l in p.stdout.splitlines() if l.startswith('PIN ')),''),'stderr':p.stderr.strip()};cases.append(item);print('LOADER_CASE',json.dumps(item))
assert [x['exit'] for x in cases]==[0,0,0,0,11];assert 'nf=1' in cases[2]['nd_nf'][0] and cases[2]['valid_claim']
# Compile unmodified status fragment and source mkdir logic with host filesystem adapters.
s=(R/'probe-r41-mkdir-meta.cpp').read_text();status=s[s.index('                    {',s.index('// R40: STATUS AGREGADO')):s.index('                    // (5) metadata',s.index('// R40: STATUS AGREGADO'))]
setup=s[s.index('                int mk41 = 0;'):s.index('                // ---- execucao com REPACK',s.index('                int mk41 = 0;'))]
cpp=r'''#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <direct.h>
static const char *parent_dir;
static std::string_dummy_unused;
'''.replace('static std::string_dummy_unused;','')+r'''
static void rep(const char* f,...) {va_list ap;va_start(ap,f);vprintf(f,ap);va_end(ap);puts("");}
static int host_mkdir(const char *p,int) {return _mkdir(strstr(p,"/data/data/")==p?parent_dir:p);}
static FILE *host_fopen(const char *p,const char *m) {char b[1024];if(strstr(p,"/data/data/")==p){snprintf(b,sizeof(b),"%s/sel.txt",parent_dir);return fopen(b,m);}return fopen(p,m);}
#define mkdir host_mkdir
#define fopen host_fopen
int main(int argc,char**argv){if(argc<3)return 2;parent_dir=argv[2];const char*dir39=argv[2];const char*attempt39="new-attempt";
if(strcmp(argv[1],"setup")==0){int K=6144,N=2048,B=1;struct{const char*nm;}ty={"q6_K"};
'''+setup+r'''
puts("AFTER_SETUP_REACHED");return 0;}
const char*exp_dir=argv[2];int exfail=argc>3?atoi(argv[3]):0;
'''+status+r'''
return 0;}
'''
cp=O/'r41-fragments.cpp';cp.write_text(cpp);xe=O/'r41-fragments.exe';p=subprocess.run(['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2',str(cp),'-o',str(xe)],capture_output=True,text=True,env=env);(O/'fragment-build.log').write_text(p.stdout+p.stderr);print('FRAGMENT_BUILD_RC',p.returncode);assert p.returncode==0,p.stderr
fs=[]
for label,meta,flag in [('fresh_no_meta',None,0),('old_meta_one_byte',b'X',0),('last_error_active',b'X',1)]:
 d=O/'fs'/label;d.mkdir(parents=True,exist_ok=True)
 for n in names:(d/n).write_bytes(b'X')
 if meta is not None:(d/'meta.txt').write_bytes(meta)
 p=subprocess.run([str(xe),'status',str(d),str(flag)],capture_output=True,text=True,env=env);fs.append({'case':label,'exit':p.returncode,'stdout':p.stdout.strip()});print('FS_CASE',json.dumps(fs[-1]))
d=O/'fs/old_meta_one_byte';(d/'sel.txt').write_text('OLD_SELECTION\n');p=subprocess.run([str(xe),'setup',str(d)],capture_output=True,text=True,env=env);setup_result={'exit':p.returncode,'stdout':p.stdout.strip(),'selector_after':(d/'sel.txt').read_text().strip()};print('SETUP_CASE',json.dumps(setup_result));assert 'SEL_FAIL' in p.stdout and 'AFTER_SETUP_REACHED' in p.stdout and 'new-attempt' in setup_result['selector_after']
result={'manifest':checks,'csv_rows':len(rows),'csv_diff_to_r39_exact':diffs,'metrics':metrics,'top10':top_info,'loader_cases':cases,'filesystem_cases':fs,'setup_case':setup_result,'scope':'host replay of R39 and source-fragment counterexamples; no app/ADB/DSP changes'};(O/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('AUDIT_DONE_RC0')
