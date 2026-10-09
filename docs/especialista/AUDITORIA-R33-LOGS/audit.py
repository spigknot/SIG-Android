from pathlib import Path
import subprocess,re,hashlib,shutil,os,json
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada33')
OUT=Path(__file__).resolve().parent
src=(ROOT/'r33_flow_test.c').read_text()
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
records=[]
for name,code,flags in [('green',src,[]),('m7',src,['-DMUTANTE_SEM_IF_BW']),('m8',src.replace('return (opstage & HTP_OPSTAGE_QUEUE) && (opstage & HTP_OPSTAGE_COMPUTE);','return 1;'),[]),('poisoned_extracted_inc',src,[])]:
 wd=OUT/name;wd.mkdir(exist_ok=True)
 (wd/'r33_flow_test.c').write_text(code)
 if name=='poisoned_extracted_inc': (wd/'r33_alloc_gen.inc').write_text('#error EXTRACTED_BLOCK_NOT_COMPILED\n')
 else:shutil.copyfile(ROOT/'r33_alloc_gen.inc',wd/'r33_alloc_gen.inc')
 cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2',*flags,'-I'+wd.as_posix(),(wd/'r33_flow_test.c').as_posix(),'-o',(wd/'test.exe').as_posix()]
 r=subprocess.run(cmd,capture_output=True,text=True,env=env);(wd/'build.log').write_text(r.stdout+r.stderr)
 print(name,'BUILD_EXIT',r.returncode)
 if r.returncode:raise RuntimeError(r.stderr[-1000:])
 r=subprocess.run([(wd/'test.exe').as_posix()],capture_output=True,text=True,env=env)
 (wd/'run.log').write_text(r.stdout+r.stderr)
 print(name,'RUN_EXIT',r.returncode,'SUMMARY',[l for l in r.stdout.splitlines() if 'TOTAL:' in l])
 records.append({'name':name,'exit':r.returncode,'stdout':r.stdout})
assert [r['exit'] for r in records]==[0,1,1,0]
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST',len(checks),'MATCH',sum(ok for _,ok in checks))
print('GENERATED_INC_BRACES', (ROOT/'r33_alloc_gen.inc').read_text().count('{'),(ROOT/'r33_alloc_gen.inc').read_text().count('}'))
s=(Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada32/export/m6/r29_settensor_adap.inc')).read_text()
code_only=re.sub(r'//[^\n]*|/\*.*?\*/','',s,flags=re.S)
print('M6_SUBSTRING_VALIDATOR_PASSES','GGML_ABORT' not in s and 'silent!' in s)
print('M6_ACTUAL_ABORT_CALLS',len(re.findall(r'\bGGML_ABORT\s*\(',code_only)))
(OUT/'results.json').write_text(json.dumps({'runs':records,'matching_hashes':sum(ok for _,ok in checks),'m6_substring_pass':False,'m6_actual_abort_calls':len(re.findall(r'\bGGML_ABORT\s*\(',code_only))},indent=2))
assert all(ok for _,ok in checks)
print('NOTE: independent scratch-only experiments; standalone replica results, not device or real probe flow validation.')
