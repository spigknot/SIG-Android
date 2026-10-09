from pathlib import Path
import subprocess,shutil,os,re,json,hashlib
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada34')
OUT=Path(__file__).resolve().parent
ENV=os.environ.copy();ENV['PATH']='C:/msys64/mingw64/bin;'+ENV.get('PATH','')
records=[]
for name,flags in [('green',[]),('m7',['-DMUTANTE_SEM_IF_BW']),('m8',['-DMUTANTE_BYPASS_OPSTAGE']),('poison',[])]:
 wd=OUT/name;wd.mkdir(exist_ok=True)
 for f in ['flow_seam.h','r34_flow_test.c']:shutil.copyfile(ROOT/f,wd/f)
 if name=='poison':
  p=wd/'flow_seam.h';p.write_text('#error POISON_SHARED_HEADER\n'+p.read_text())
 cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2',*flags,'-I'+wd.as_posix(),(wd/'r34_flow_test.c').as_posix(),'-o',(wd/'test.exe').as_posix()]
 r=subprocess.run(cmd,capture_output=True,text=True,env=ENV);(wd/'build.log').write_text(r.stdout+r.stderr)
 print(name,'BUILD_EXIT',r.returncode)
 if name=='poison':
  assert r.returncode!=0 and 'POISON_SHARED_HEADER' in r.stderr
  records.append({'case':name,'build_exit':r.returncode});continue
 assert r.returncode==0,r.stderr[-1000:]
 r=subprocess.run([(wd/'test.exe').as_posix()],capture_output=True,text=True,env=ENV);(wd/'run.log').write_text(r.stdout+r.stderr)
 print(name,'RUN_EXIT',r.returncode,'SUMMARY',[l for l in r.stdout.splitlines() if 'TOTAL:' in l])
 records.append({'case':name,'build_exit':0,'run_exit':r.returncode,'stdout':r.stdout})
assert [r.get('run_exit') for r in records[:3]]==[0,1,1]
p=(ROOT/'probe-r34-com-seam.cpp').read_text()
print('PROBE_ALLOC_CALLS',len(re.findall(r'\bflow_alloc_preflight\s*\(',p)))
print('PROBE_OPSTAGE_CALLS',len(re.findall(r'\bflow_opstage_preflight\s*\(',p)))
print('ASSIGNED_UPLOAD_COUNTER', 'res->upload_calls = 1;' in (ROOT/'flow_seam.h').read_text())
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST',len(checks),'MATCH',sum(ok for _,ok in checks))
assert all(ok for _,ok in checks)
(OUT/'results.json').write_text(json.dumps(records,indent=2))
print('NOTE: actual shared alloc decision compiled; upload/compute are assigned indicators, opstage predicate offline only. Scratch-only, no device.')
