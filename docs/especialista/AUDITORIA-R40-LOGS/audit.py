from pathlib import Path
import hashlib,re,json,subprocess,zipfile,collections,shutil
ROOT=Path('D:/Projetos/SIG'); R40=ROOT/'docs/npu-hymt2/smart-rodada40'; OUT=Path(__file__).resolve().parent
records=[]
for line in (R40/'MANIFESTO-SHA256.txt').read_text().splitlines():
 m=re.match(r'^([0-9a-f]{64})\s+([^ ]+)(?:\s+\((\d+) B\))?$',line)
 if not m:continue
 h,name,size=m.groups();p=(ROOT/'docs/especialista'/name.split(':',1)[1]) if name.startswith('relatorio:') else R40/name
 if not p.is_file():raise RuntimeError('Missing manifest target: '+str(p))
 actual=hashlib.sha256(p.read_bytes()).hexdigest();actual_size=p.stat().st_size
 assert actual==h,(str(p),actual,h)
 if size is not None:assert int(size)==actual_size
 records.append({'target':str(p),'size':actual_size,'sha256':actual})
print('MANIFEST_HASH_RECORDS',len(records),'ALL_OK',True)
source=(R40/'probe-r40-com-ids.cpp').read_text();live=Path('C:/npu-probe/app/src/main/cpp/probe.cpp')
live_id={'archived_sha256':hashlib.sha256((R40/'probe-r40-com-ids.cpp').read_bytes()).hexdigest(),'live_sha256':hashlib.sha256(live.read_bytes()).hexdigest() if live.is_file() else None}
live_id['equal']=live_id['archived_sha256']==live_id['live_sha256'];print('LIVE_SOURCE',live_id)
apk=Path('C:/npu-probe/app/build/outputs/apk/debug/app-debug.apk');host=[]
if apk.is_file():
 print('LOCAL_APK',apk.stat().st_size,hashlib.sha256(apk.read_bytes()).hexdigest())
 with zipfile.ZipFile(apk) as z:
  for name in z.namelist():
   if name.startswith('lib/') and name.endswith('.so'):
    b=z.read(name);item={'name':name,'size':len(b),'sha256':hashlib.sha256(b).hexdigest()}
    if 'npuprobe' in name:item['R40_marker_present']=b'R40 SNAPSHOT_COMPLETE' in b
    host.append(item)
 print('LOCAL_APK_LIBS',json.dumps(host))
# Extract actual source fragments, with only stdio/PID host adapters.
id_fragment=source[source.index('                static std::atomic<long> g_attempt_seq{0};'):source.index('                char dir39[256];')]
status_fragment=source[source.index('                    {',source.index('// R40: STATUS AGREGADO')):source.index('                    // (5) metadata',source.index('// R40: STATUS AGREGADO'))]
mkdir_fragment='(void) mkdir(dir39, 0770);'
assert mkdir_fragment in source
cpp=r'''#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <string>
#include <atomic>
#include <direct.h>
#include <cerrno>
static int fake_getpid() { return 4242; }
#define getpid fake_getpid
static void rep(const char * fmt, ...) { va_list ap; va_start(ap,fmt); vprintf(fmt,ap); va_end(ap); puts(""); }
static int host_mkdir(const char * p,int) { int rc=_mkdir(p); printf("HOST_MKDIR rc=%d errno=%d\n",rc,errno); return rc; }
#define mkdir host_mkdir
static std::string next_id() {
'''+id_fragment+r'''
 return attempt39;
}
int main(int argc,char **argv) {
 if (argc<2) return 2;
 if (strcmp(argv[1],"--ids")==0) { int n=argc>2?atoi(argv[2]):1; for(int i=0;i<n;i++)puts(next_id().c_str()); return 0; }
 if (strcmp(argv[1],"--mkdir")==0) { const char *dir39=argv[2];
'''+mkdir_fragment+r'''
 puts("AFTER_ORIGINAL_MKDIR_REACHED"); return 0; }
 const char *exp_dir=argv[1];const char *attempt39="audit-attempt";
 int exfail=argc>2?atoi(argv[2]):0;
'''+status_fragment+r'''
 printf("HARNESS_AFTER exfail=%d\n",exfail); return 0;
}
'''
cpp_path=OUT/'r40-extracted-fragments.cpp';cpp_path.write_text(cpp)
exe=OUT/'r40-extracted-fragments.exe';compiler=Path('C:/msys64/mingw64/bin/g++.exe')
import os
os.environ['PATH']=str(compiler.parent)+os.pathsep+os.environ.get('PATH','')
compile_run=subprocess.run([str(compiler),'-std=c++17','-O2',str(cpp_path),'-o',str(exe)],capture_output=True,text=True)
(OUT/'compile.log').write_text(compile_run.stdout+compile_run.stderr);print('HOST_FRAGMENT_BUILD_RC',compile_run.returncode)
assert compile_run.returncode==0
names=['canonweights_9216.bin','actfloat_131072.bin','cpuout_32768.bin','htpout_32768.bin','tiled_raw.bin'];cases=[]
for label,blob_names,meta,exfail in [('one_byte_each_no_meta',names,None,0),('short_write_flag_ignored',names,None,1),('wrong_meta_and_stale_raw',names,'attempt=other K=1 N=1 B=99 type=F32\n',0),('raw_missing',names[:-1],None,0)]:
 d=OUT/'fixtures'/label;d.mkdir(parents=True,exist_ok=True)
 for n in names:
  if (d/n).exists():(d/n).unlink()
 for n in blob_names:(d/n).write_bytes(b'X')
 if meta is not None:(d/'meta.txt').write_text(meta)
 elif (d/'meta.txt').exists():(d/'meta.txt').unlink()
 p=subprocess.run([str(exe),str(d),str(exfail)],capture_output=True,text=True)
 item={'case':label,'complete':bool('SNAPSHOT_COMPLETE' in p.stdout),'exit':p.returncode,'stdout':p.stdout.strip(),'fixture_scope':'synthetic filesystem, not numeric capture'};cases.append(item);print('FS_CASE',json.dumps(item))
assert [x['complete'] for x in cases]==[True,True,True,False]
p1=subprocess.run([str(exe),'--ids','10000'],capture_output=True,text=True);ids=p1.stdout.splitlines();assert p1.returncode==0 and len(set(ids))==len(ids)==10000
p2=subprocess.run([str(exe),'--ids','1'],capture_output=True,text=True);collision=ids[0]==p2.stdout.strip();assert collision
print('ID_SAME_PROCESS',len(ids),'UNIQUE',len(set(ids)),'SAME_PID_NEW_PROCESS_COLLISION',collision,'FIRST_ID',ids[0])
d=OUT/'fixtures/one_byte_each_no_meta';p=subprocess.run([str(exe),'--mkdir',str(d)],capture_output=True,text=True);assert 'rc=-1' in p.stdout and 'AFTER_ORIGINAL_MKDIR_REACHED' in p.stdout
print('EEXIST_COUNTEREXAMPLE',p.stdout.strip().replace('\n',' | '),'EXIT',p.returncode)
# Full IDs/cohort are local, not proof of Android loading or HTP execution.
result={'manifest':records,'live_source':live_id,'local_apk_libs':host,'host_fragment_compile_rc':compile_run.returncode,'filesystem_cases':cases,'id_count':len(ids),'unique_id_count':len(set(ids)),'same_pid_process_restart_collision':collision,'mkdir_stdout':p.stdout,'scope':'host source-fragment counterexamples; no app edit, ADB, Android execution, or DSP trace'}
(OUT/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('AUDIT_RC_0_COUNTEREXAMPLES_CONFIRMED')
