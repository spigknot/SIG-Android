from pathlib import Path
import os,subprocess,re,hashlib,json,shutil,struct,math
R=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada42');C=R.parent/'smart-rodada39/captura-q6-k6144';G=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml');O=Path(__file__).resolve().parent
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','');checks=[]
for h,n in re.findall(r'(?m)^([0-9a-f]{64})\s+(\S+)',(R/'MANIFESTO-SHA256.txt').read_text()):
 p=R/n if not n.startswith('relatorio:') else R.parent.parent/'especialista'/n.split(':',1)[1]
 assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==h;checks.append({'name':n,'sha256':h,'size':p.stat().st_size})
print('MANIFEST',len(checks),'ALL_OK')
incs=['-I'+str(G/'include'),'-I'+str(G/'src')];q=O/'quants.o';exe=O/'r42_refs.exe';commands=[['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*incs,'-c',str(G/'src/ggml-quants.c'),'-o',str(q)],['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*incs,str(R/'r42_refs.c'),str(q),'-Wl,--gc-sections','-o',str(exe)]]
for i,cmd in enumerate(commands):
 p=subprocess.run(cmd,capture_output=True,text=True,env=env);(O/f'build{i}.log').write_text(p.stdout+p.stderr);print('BUILD',i,'RC',p.returncode);assert p.returncode==0,p.stderr[-1000:]
names=['canonweights_9216.bin','actfloat_131072.bin','cpuout_32768.bin','htpout_32768.bin','tiled_raw.bin']; cases=[]
for label in ['normal','nan','badmeta','zeroact']:
 d=O/'cases'/label;d.mkdir(parents=True,exist_ok=True)
 for name in names:shutil.copyfile(C/name,d/name)
 (d/'meta.txt').write_text('attempt=audit K=6144 N=2048 B=1 type=q6_K\n' if label!='badmeta' else 'attempt=audit K=256 N=64 B=128 type=q4_K\n')
 if label=='nan':
  with (d/'htpout_32768.bin').open('r+b') as f:f.write(struct.pack('<f',math.nan))
 if label=='zeroact':(d/'actfloat_131072.bin').write_bytes(bytes(24576))
 p=subprocess.run([str(exe),str(d)],capture_output=True,text=True,env=env);(O/f'{label}.out').write_text(p.stdout);(O/f'{label}.err').write_text(p.stderr)
 item={'case':label,'exit':p.returncode,'pin_emitted':'PIN(' in p.stdout,'stderr':p.stderr.strip(),'stdout_lines':len(p.stdout.splitlines())};cases.append(item);print('CASE',json.dumps(item))
assert [x['exit'] for x in cases]==[0,23,22,28] and all(not x['pin_emitted'] for x in cases[1:])
# Controlled digest-provider fault injection. Real captured bytes unchanged.
# Original loader source remains byte-for-byte intact; only popen/pclose redirected.
entries=[]
for name in names:
 h=hashlib.sha256((C/name).read_bytes()).hexdigest();bad=h[:40]+('0'*24 if h[40:]!='0'*24 else '1'*24);entries.append({'name':name,'actual_full_sha256':h,'provider_full_sha256':bad})
header='#include <stdio.h>\n#include <string.h>\nstatic FILE *audit_popen(const char *cmd,const char *mode){(void)mode;FILE*f=tmpfile();if(!f)return NULL;\n'
for x in entries:header+='if(strstr(cmd,"'+x['name']+'"))fputs("'+x['provider_full_sha256']+'\\n",f);\n'
header+='rewind(f);return f;}\nstatic int audit_pclose(FILE*f){return fclose(f);}\n#define popen audit_popen\n#define pclose audit_pclose\n'
hp=O/'digest-provider-injection.h';hp.write_text(header);xe=O/'r42-digest-injection.exe'
p=subprocess.run(['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections','-include',str(hp),*incs,str(R/'r42_refs.c'),str(q),'-Wl,--gc-sections','-o',str(xe)],capture_output=True,text=True,env=env);(O/'digest-build.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr[-1000:]
p=subprocess.run([str(xe),str(O/'cases/normal'),'--reconstruct-r39'],capture_output=True,text=True,env=env);(O/'digest-injection.out').write_text(p.stdout);(O/'digest-injection.err').write_text(p.stderr);assert p.returncode==0
print('DIFFERENT_FULL_SHA_SAME_40_PREFIX_ACCEPTED',p.returncode,'FILES',len(entries))
# Exercise runner control flow in sandbox: paths-only adapter, injected failing compiler,
# and preexisting diagnostic executable. Never use or modify /root or system temp.
sandbox=O/'runner-sandbox';sandbox.mkdir(exist_ok=True);logs=sandbox/'logs';logs.mkdir(exist_ok=True)
script=(R/'r42_runner.sh').read_text().replace('/root',sandbox.as_posix()).replace('/tmp',logs.as_posix());adapt=O/'runner-path-adapter.sh';adapt.write_text(script)
shutil.copyfile(R/'r42_refs.c',sandbox/'r42_refs.c');stale=sandbox/'r42_refs_bin';stale.write_text('#!/bin/bash\nprintf "STALE_BIN_EXECUTED\\n"\nexit 0\n')
launch='chmod +x "'+stale.as_posix()+'"; function g++(){ printf "error: injected compiler failure\\n" >&2; return 7; }; export -f g++; bash "'+adapt.as_posix()+'" "'+(O/'cases/normal').as_posix()+'"'
p=subprocess.run(['bash','-c',launch],capture_output=True,text=True,env=env);(O/'runner-failed-build.out').write_text(p.stdout);(O/'runner-failed-build.err').write_text(p.stderr)
runner={'exit':p.returncode,'build7':'BUILD_EXIT=7' in p.stdout,'run0':'RUN_EXIT=0' in p.stdout,'stale_executed':'STALE_BIN_EXECUTED' in p.stdout,'valid_claim':'RUNNER_STATUS=OBSERVACAO_VALIDA' in p.stdout,'scope':'paths adapted + injected compiler failure + controlled stale diagnostic executable'};print('RUNNER_FAILED_BUILD',json.dumps(runner));assert p.returncode==0 and all(runner[k] for k in ['build7','run0','stale_executed','valid_claim'])
current=Path('C:/npu-probe/app/src/main/cpp/probe.cpp').read_bytes();(O/'probe-current-observed.cpp').write_bytes(current)
log_prefix=re.findall(r'SRC_SHA: ([0-9a-f]+)',(R/'r42-bateria-casos.txt').read_text());old_normal_prefix=re.findall(r'SRC_SHA: ([0-9a-f]+)',(R/'r42-normal-full.txt').read_text())
result={'manifest':checks,'loader_cases':cases,'digest_injection':entries,'digest_injection_exit':0,'runner_failed_build':runner,'batch_src_prefixes':sorted(set(log_prefix)),'normal_file_src_prefix':old_normal_prefix,'observed_probe_source_sha256':hashlib.sha256(current).hexdigest(),'scope':'read-only primary files; host loader regressions and controlled runner/digest counterexamples, no ADB or DSP execution'};(O/'results.json').write_text(json.dumps(result,indent=2));print('AUDIT_DONE_RC0')
