from pathlib import Path
import subprocess, shutil, hashlib, re, json, os
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada29')
BASE=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
OUT=Path(__file__).resolve().parent
inc=['-I'+(BASE/'include').as_posix(),'-I'+(BASE/'src').as_posix()]
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
cmd=['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*inc,'-c',(BASE/'src/ggml-quants.c').as_posix(),'-o',(OUT/'quants.o').as_posix()]
r=subprocess.run(cmd,capture_output=True,text=True,env=env);(OUT/'quants-build.log').write_text(r.stdout+r.stderr)
assert r.returncode==0,r.stderr[-1000:]
original=(ROOT/'r29_settensor_gen.inc').read_text()
adapted=original.replace('static void ggml_backend_hexagon_buffer_set_tensor(', 'static void set_tensor_real(')
adapted=adapted.replace('    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;\n    auto sess = sbuf->sess;\n', '    // ADAPTADO: contexto externo nao exercitado.\n    (void) buffer;\n')
adapted=re.sub(r'    HEX_VERBOSE\("ggml-hex: %s set-tensor.*?\n', '    // Log de sessao nao exercitado.\n', adapted)
assert 'if (offset != 0 || size !=' in adapted
assert 'repack_q4_K_tiled(tensor, data, offset, size)' in adapted
records=[]
for name,text in [('delivered', (ROOT/'r29_settensor_adap.inc').read_text()),('regenerated_from_guarded_gen',adapted)]:
    wd=OUT/name;wd.mkdir(exist_ok=True)
    for f in ['r29_fixture.c','r29_cores_gen.h']:shutil.copyfile(ROOT/f,wd/f)
    (wd/'r29_settensor_adap.inc').write_text(text)
    cmd=['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-fpermissive','-O2','-ffunction-sections','-fdata-sections',*inc,'-I'+wd.as_posix(),(wd/'r29_fixture.c').as_posix(),(OUT/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(wd/'fx.exe').as_posix()]
    r=subprocess.run(cmd,capture_output=True,text=True,env=env);(wd/'build.log').write_text(r.stdout+r.stderr)
    print(name,'BUILD_EXIT',r.returncode)
    if r.returncode:raise RuntimeError(r.stderr[-1800:])
    r=subprocess.run([(wd/'fx.exe').as_posix()],capture_output=True,text=True,env=env)
    (wd/'stdout.log').write_text(r.stdout);(wd/'stderr.log').write_text(r.stderr)
    print(name,'RUN_EXIT',r.returncode)
    print('\n'.join(l for l in r.stdout.splitlines() if 'T4' in l or 'TOTAL:' in l))
    records.append({'name':name,'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr,'callback_sha256':hashlib.sha256((wd/'r29_settensor_adap.inc').read_bytes()).hexdigest()})
assert records[0]['exit']==1 and 'TOTAL: 2 RED' in records[0]['stdout']
assert records[1]['exit']==0 and 'TOTAL: 0 RED' in records[1]['stdout']
(OUT/'results.json').write_text(json.dumps(records,indent=2))
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST',len(checks),'MATCH',sum(ok for _,ok in checks))
print('NOTE: independent local canonical implementation; no app/probe/candidate changes or device actions.')
