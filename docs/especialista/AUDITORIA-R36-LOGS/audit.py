from pathlib import Path
import subprocess, hashlib, re, os, json, struct, math
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada36')
CAP=ROOT/'captura';OUT=Path(__file__).resolve().parent
BASE=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
expected={'canonweights_9216.bin':9216,'actfloat_131072.bin':131072,'cpuout_32768.bin':32768,'htpout_32768.bin':32768,'tiled_raw.bin':10240}
for f,size in expected.items():
 b=(CAP/f).read_bytes();assert len(b)==size,(f,len(b));print('FILE_OK',f,len(b))
 if 'float' in f or 'out_' in f: assert all(math.isfinite(x[0]) for x in struct.iter_unpack('<f',b)),f
checks=[]
for h,f in re.findall(r'(?m)^([0-9a-f]{64})\s+\*?(\S+)',(CAP/'SHA256-BINARIOS.txt').read_text()):
 checks.append((f,hashlib.sha256((CAP/f).read_bytes()).hexdigest()==h))
print('CAPTURE_HASHES',len(checks),'MATCH',sum(ok for _,ok in checks));assert all(ok for _,ok in checks)
text=(ROOT/'r36_refs.c').read_text().replace('/root/r36_cap',CAP.as_posix())
# Keep supplied algorithms, add diagnostics over all entries and export references.
text=text.replace('double maxabs = 0, sum2 = 0, sum2ref = 0; long nd = 0, nf = 0;', 'double maxabs = 0, sum2 = 0, sum2ref = 0; long nd = 0, nf = 0, legacy_viol = 0;')
text=text.replace('if (d > maxabs) maxabs = d;', 'if (d > maxabs) maxabs = d; if (d > 0.02 + 0.05*fabs(r)) legacy_viol++;')
text=text.replace('cmps[c].nome, nd, nf, maxabs, sum2ref > 0 ? sum2 / sum2ref : -1.0);','cmps[c].nome, nd, nf, maxabs, sum2ref > 0 ? sum2 / sum2ref : -1.0); printf("  legacy_viol=%ld\\n", legacy_viol);')
# Outputs only of independent local reference calculations, not new device data.
extra='''    FILE *fs=fopen("%s/rscalar.f64", "wb"), *ft=fopen("%s/rtiled.f64", "wb");
    if(!fs||!ft)return 3;
    if(fwrite(rscalar,sizeof(double),N*B,fs)!=(size_t)(N*B))return 3;
    if(fwrite(rtiled,sizeof(double),N*B,ft)!=(size_t)(N*B))return 3;
    fclose(fs);fclose(ft);
'''%(OUT.as_posix(),OUT.as_posix())
text=text.replace('    // comparacoes (TODAS as 8192!)',extra+'    // comparacoes (TODAS as 8192!)') if '    // comparacoes (TODAS as 8192!)' in text else text
# Insert by unambiguous struct marker if the comment has additional punctuation.
if 'FILE *fs=' not in text:
 marker='    struct { const char * nome; float * out; double * ref; } cmps[4] = {'
 assert marker in text;text=text.replace(marker,extra+marker)
(OUT/'refs.cpp').write_text(text)
inc=['-I'+(BASE/'include').as_posix(),'-I'+(BASE/'src').as_posix()]
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
commands=[['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*inc,'-c',(BASE/'src/ggml-quants.c').as_posix(),'-o',(OUT/'quants.o').as_posix()],['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-ffunction-sections','-fdata-sections',*inc,(OUT/'refs.cpp').as_posix(),(OUT/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(OUT/'refs.exe').as_posix()]]
for i,cmd in enumerate(commands):
 r=subprocess.run(cmd,capture_output=True,text=True,env=env);(OUT/f'build{i}.log').write_text(r.stdout+r.stderr);print('BUILD',i,'EXIT',r.returncode);assert r.returncode==0,r.stderr[-1800:]
r=subprocess.run([(OUT/'refs.exe').as_posix()],capture_output=True,text=True,env=env);(OUT/'run.log').write_text(r.stdout+r.stderr);print('RUN_EXIT',r.returncode);print(r.stdout);assert r.returncode==0
assert r.stdout.count('nd=8192 nf=0')==4
rs=[x[0] for x in struct.iter_unpack('<d',(OUT/'rscalar.f64').read_bytes())]
rt=[x[0] for x in struct.iter_unpack('<d',(OUT/'rtiled.f64').read_bytes())]
assert len(rs)==len(rt)==8192 and all(map(math.isfinite,rs+rt))
metrics={'Rtiled_vs_Rscalar':{'nmse':sum((a-b)**2 for a,b in zip(rt,rs))/sum(a*a for a in rs),'max_abs':max(abs(a-b) for a,b in zip(rt,rs))}}
print('ADDITIONAL',metrics)
(OUT/'results.json').write_text(json.dumps({'capture_hashes':checks,'additional':metrics,'stdout':r.stdout,'exit':r.returncode},indent=2))
print('NOTE: local canonical implementation, supplied direct reader unchanged; hashes/bytes real. No app/probe/candidate/device changes.')
