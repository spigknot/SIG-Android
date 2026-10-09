from pathlib import Path
import subprocess, shutil, hashlib, re, json, os
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada30')
OUT=Path(__file__).resolve().parent
ENV=os.environ.copy(); ENV['PATH']='C:/msys64/mingw64/bin;'+ENV.get('PATH','')
records=[]
for name,source,includes,edit in [
 ('gate','r30_gate_test.c',['r30_gate.h'],False),
 ('fused','r30_fused_test.c',['r30_merge_gen.inc','r30_rowsize_gen.inc'],False),
 ('fused_without_q4k','r30_fused_test.c',['r30_merge_gen.inc','r30_rowsize_gen.inc'],True),
]:
 wd=OUT/name; wd.mkdir(exist_ok=True)
 for f in [source,*includes]: shutil.copyfile(ROOT/f,wd/f)
 if edit:
  p=wd/'r30_rowsize_gen.inc'; t=p.read_text(); assert ' || wtype == GGML_TYPE_Q4_K' in t
  p.write_text(t.replace(' || wtype == GGML_TYPE_Q4_K',''))
 exe=wd/'test.exe'
 cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-I'+wd.as_posix(),(wd/source).as_posix(),'-o',exe.as_posix()]
 r=subprocess.run(cmd,capture_output=True,text=True,env=ENV);(wd/'build.log').write_text(r.stdout+r.stderr)
 print(name,'BUILD_EXIT',r.returncode)
 if r.returncode:raise RuntimeError(r.stderr[-1500:])
 r=subprocess.run([exe.as_posix()],capture_output=True,text=True,env=ENV)
 (wd/'run.log').write_text(r.stdout+r.stderr)
 checks=len(re.findall(r'\[(?:OK |RED)\]',r.stdout))
 print(name,'RUN_EXIT',r.returncode,'PRINTED_CHECKS',checks)
 print('\n'.join(l for l in r.stdout.splitlines() if 'TOTAL:' in l or 'RED]' in l))
 records.append({'case':name,'exit':r.returncode,'stdout':r.stdout})
assert [r['exit'] for r in records]==[0,0,1]
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST_FILES',len(checks),'MATCH',sum(ok for _,ok in checks))
mat=[]
for name in ['green','m1','m2','m3','m4','m5']:
 t=(ROOT/'entrega-real-r29'/name/'r29_settensor_adap.inc').read_text()
 mat.append((name,t.count('if (offset != 0 || size !=')))
print('EXPORT_GUARD_MATRIX',mat)
print('FUSED_SCOPE: extracted predicate + one row_size expression with stubbed helpers; not full precompute/consumer/runtime proof.')
(OUT/'results.json').write_text(json.dumps(records,indent=2))
