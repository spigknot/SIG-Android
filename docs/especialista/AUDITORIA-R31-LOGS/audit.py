from pathlib import Path
import re, subprocess, shutil, hashlib, json, os
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada31')
R29=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada29')
OUT=Path(__file__).resolve().parent
WORK=OUT/'root';WORK.mkdir(exist_ok=True)
for f in ['r29_settensor_gen.inc','r29_fixture.c','r29_cores_gen.h']:shutil.copyfile(R29/f,WORK/f)
for name in ['sigcand2','kq2']:
 p=WORK/name/'ggml/src/ggml-hexagon/ggml-hexagon.cpp';p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(R29/'r29_settensor_gen.inc',p)
# Reexecute supplied patch/pack only in scratch with every /root path remapped.
s=(ROOT/'r31_ga.py').read_text().replace('/root/',WORK.as_posix()+'/')
exec(compile(s,'r31_ga.py-remapped','exec'),{})
new=(WORK/'sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp').read_text()
assert new.count('GGML_ABORT(')==2
(WORK/'r29_settensor_gen.inc').write_text(new)
s=(ROOT/'r31_pack.py').read_text().replace('/root/',WORK.as_posix()+'/')
exec(compile(s,'r31_pack.py-remapped','exec'),{})
base=WORK/'r31_t'
wd=base/'silent_refusal';wd.mkdir(exist_ok=True)
for f in ['r31_fixture.c','r29_cores_gen.h','r29_settensor_adap.inc']:shutil.copyfile(base/'green'/f,wd/f)
p=wd/'r29_settensor_adap.inc';t=p.read_text()
t,n=re.subn(r'GGML_ABORT\((.*?)\);\n',lambda m:'fprintf(stderr, '+m[1]+');\n                break;\n',t,flags=re.S)
assert n==2,n
p.write_text(t)
BASE=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
inc=['-I'+(BASE/'include').as_posix(),'-I'+(BASE/'src').as_posix()]
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
r=subprocess.run(['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*inc,'-c',(BASE/'src/ggml-quants.c').as_posix(),'-o',(OUT/'quants.o').as_posix()],capture_output=True,text=True,env=env)
(OUT/'quants-build.log').write_text(r.stdout+r.stderr);assert r.returncode==0,r.stderr[-1000:]
records=[]
for name in ['green','m5','silent_refusal']:
 wd=base/name
 cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-fpermissive','-ffunction-sections','-fdata-sections',*inc,'-I'+wd.as_posix(),(wd/'r31_fixture.c').as_posix(),(OUT/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(wd/'test.exe').as_posix()]
 r=subprocess.run(cmd,capture_output=True,text=True,env=env);(wd/'build.log').write_text(r.stdout+r.stderr)
 if r.returncode:raise RuntimeError(r.stderr[-1500:])
 r=subprocess.run([(wd/'test.exe').as_posix()],capture_output=True,text=True,env=env)
 (wd/'run.log').write_text(r.stdout+r.stderr)
 print('SCENARIO',name,'EXIT',r.returncode)
 print('\n'.join(l for l in r.stdout.splitlines() if 'T4' in l or 'TOTAL:' in l))
 records.append({'name':name,'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
assert [r['exit'] for r in records]==[0,1,0]
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST',len(checks),'MATCH',sum(ok for _,ok in checks))
s=(ROOT/'r31-bateria-device.txt').read_text()
rx=r'NpuProbe\((\d+)\): MM verdict_legacy=(\w+) verdict_pin=(\w+) pin_valid=(\d+) nmse_full=([0-9.e+-]+) \(nd=(\d+)/(\d+) nf=(\d+)/(\d+) unw=(\d+) novl=(\d+)\)'
raw=re.findall(rx,s);unique=list(dict.fromkeys(raw))
print('VERDICT_LINES',len(raw),'UNIQUE',len(unique),'PIN_PASS',sum(r[2]=='PIN_PASS' for r in unique),'LEGACY_FAIL',sum(r[1]=='LEGACY_FAIL' for r in unique))
assert len(raw)==12 and len(unique)==6
print('GATE_HEADER_IDENTICAL_R30', (ROOT/'r30_gate-no-probe.h').read_bytes()==Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada30/r30_gate.h').read_bytes())
(OUT/'results.json').write_text(json.dumps({'fixture_runs':records,'dedup_verdicts':unique},indent=2))
print('NOTE: scripts replayed on archived input copies and local canonical implementation, not an identification of the device ELF or missing server fixture files.')
