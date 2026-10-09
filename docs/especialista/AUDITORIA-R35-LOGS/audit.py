from pathlib import Path
import subprocess,shutil,hashlib,re,json,os
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada35')
OUT=Path(__file__).resolve().parent
ENV=os.environ.copy();ENV['PATH']='C:/msys64/mingw64/bin;'+ENV.get('PATH','')
records=[]
for name,flags in [('green',[]),('m7',['-DMUTANTE_SEM_IF_BW']),('m8',['-DMUTANTE_BYPASS_OPSTAGE'])]:
 wd=OUT/name;wd.mkdir(exist_ok=True)
 for f in ['flow_seam.h','r35_flow_test.c']:shutil.copyfile(ROOT/f,wd/f)
 r=subprocess.run(['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2',*flags,'-I'+wd.as_posix(),(wd/'r35_flow_test.c').as_posix(),'-o',(wd/'test.exe').as_posix()],capture_output=True,text=True,env=ENV)
 (wd/'build.log').write_text(r.stdout+r.stderr);assert r.returncode==0,r.stderr[-1000:]
 r=subprocess.run([(wd/'test.exe').as_posix()],capture_output=True,text=True,env=ENV);(wd/'run.log').write_text(r.stdout+r.stderr)
 print(name,'RUN_EXIT',r.returncode,'SUMMARY',[l for l in r.stdout.splitlines() if 'TOTAL:' in l])
 records.append({'case':name,'exit':r.returncode,'stdout':r.stdout})
assert [r['exit'] for r in records]==[0,1,1]
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST',len(checks),'MATCH',sum(ok for _,ok in checks));assert all(ok for _,ok in checks)
f=(ROOT/'r35_flow_test.c').read_text();p=(ROOT/'probe-r35-com-opstage.cpp').read_text()
print('OPSTAGE_REAL_CALLER_USES',len(re.findall(r'\bflow_opstage_preflight\s*\(',p)))
print('LOCAL_TEST_CALLER_NOT_IN_PROBE','caller_flow(' not in p)
print('LOCAL_IO_DIRECT_INCREMENTS','io_upload++;' in f and 'io_compute++;' in f)
sizes={'Q4_canonical':64*144,'Q4_tiled':64*160,'act_f32':256*128*4,'cpu_out_f32':64*128*4,'htp_out_f32':64*128*4}
print('SNAPSHOT_Q4_K256_N64_B128_BYTES',sizes)
(OUT/'results.json').write_text(json.dumps({'runs':records,'snapshot_expected_bytes':sizes,'hashes_match':sum(ok for _,ok in checks)},indent=2))
print('NOTE: local modeled caller reproduced; real caller branch added, not proof of initialized backend flags or actual I/O fixture. Scratch-only, no device.')
