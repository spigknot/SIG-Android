from pathlib import Path
import subprocess,hashlib,re,struct,math,json,os,collections,csv
R=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39');C=R/'captura-q6-k6144';O=Path(__file__).resolve().parent
G=Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml')
sizes={'canonweights_9216.bin':10321920,'actfloat_131072.bin':24576,'cpuout_32768.bin':8192,'htpout_32768.bin':8192,'tiled_raw.bin':11010048}
for f,n in sizes.items():
 b=(C/f).read_bytes();assert len(b)==n,(f,len(b),n)
 if 'float' in f or 'out_' in f:assert all(math.isfinite(x[0]) for x in struct.iter_unpack('<f',b)),f
checks={}
for name,base in [('capture',C),('root',R)]:
 checks[name]=[]
 fn='SHA256-BINARIOS.txt' if name=='capture' else 'MANIFESTO-SHA256.txt'
 for h,f in re.findall(r'(?m)^([0-9a-f]{64})\s+\*?(\S+)',(base/fn).read_text()):
  if f.startswith('relatorio:'):continue
  assert (base/f).is_file(),f
  checks[name].append((f,hashlib.sha256((base/f).read_bytes()).hexdigest()==h))
 assert all(ok for f,ok in checks[name]);print('HASHES',name,len(checks[name]),'MATCH',sum(ok for f,ok in checks[name]))
s=(R/'r39_refs.c').read_text().replace('/root/r39_cap',C.as_posix())
s=s.replace('    long viol_cpu_s =', '    double rs[2048],rt[2048],rq8[2048]; block_q8_K qa[24]; float adq[6144];\n    quantize_row_q8_K_ref(act,qa,K); dequantize_row_q8_K(qa,adq,K);\n    long viol_cpu_s =',1)
s=s.replace('        const double c = (double) cpuout[n], h = (double) htpout[n];','        rs[n]=s1;rt[n]=s2;rq8[n]=0;\n        for(int k=0;k<K;k++)rq8[n]+=(double)dc[(size_t)n*K+k]*(double)adq[k];\n        const double c = (double) cpuout[n], h = (double) htpout[n];',1)
extra='''    printf("ACTUAL_HTP_VIOLATIONS scalar=%%ld tiled=%%ld\\n",viol_htp_s,viol_htp_t);
    FILE *frs=fopen("%s/rscalar.f64","wb"),*frt=fopen("%s/rtiled.f64","wb"),*fq=fopen("%s/rq8.f64","wb");
    if(!frs||!frt||!fq)return 7;
    if(fwrite(rs,8,2048,frs)!=2048||fwrite(rt,8,2048,frt)!=2048||fwrite(rq8,8,2048,fq)!=2048)return 8;
    if(fclose(frs)||fclose(frt)||fclose(fq))return 9;
'''%(O.as_posix(),O.as_posix(),O.as_posix())
assert '    printf("\\nR39_REFS_DONE\\n");' in s
s=s.replace('    printf("\\nR39_REFS_DONE\\n");',extra+'    printf("\\nR39_REFS_DONE\\n");')
(O/'refs.cpp').write_text(s)
env=os.environ.copy();env['PATH']='C:/msys64/mingw64/bin;'+env.get('PATH','')
incs=['-I'+(G/'include').as_posix(),'-I'+(G/'src').as_posix()]
commands=[['C:/msys64/mingw64/bin/gcc.exe','-std=c11','-O2','-ffunction-sections','-fdata-sections',*incs,'-c',(G/'src/ggml-quants.c').as_posix(),'-o',(O/'quants.o').as_posix()],['C:/msys64/mingw64/bin/g++.exe','-std=c++17','-O2','-ffunction-sections','-fdata-sections',*incs,(O/'refs.cpp').as_posix(),(O/'quants.o').as_posix(),'-Wl,--gc-sections','-o',(O/'refs.exe').as_posix()]]
for i,cmd in enumerate(commands):
 p=subprocess.run(cmd,capture_output=True,text=True,env=env);(O/f'build{i}.log').write_text(p.stdout+p.stderr);print('BUILD',i,'EXIT',p.returncode);assert p.returncode==0,p.stderr[-1500:]
p=subprocess.run([(O/'refs.exe').as_posix()],capture_output=True,text=True,env=env);(O/'run.log').write_text(p.stdout+p.stderr);print('RUN_EXIT',p.returncode);print(p.stdout);assert p.returncode==0 and 'nd=2048 nf=0' in p.stdout
arrays={k:[x[0] for x in struct.iter_unpack('<d',(O/(f+'.f64')).read_bytes())] for k,f in [('scalar','rscalar'),('tiled','rtiled'),('q8act','rq8')]}
for k,f in [('cpu','cpuout_32768.bin'),('htp','htpout_32768.bin')]: arrays[k]=[x[0] for x in struct.iter_unpack('<f',(C/f).read_bytes())]
assert all(len(v)==2048 and all(map(math.isfinite,v)) for v in arrays.values())
metrics={}
for out,ref in [('cpu','scalar'),('cpu','tiled'),('htp','scalar'),('htp','tiled'),('cpu','q8act'),('tiled','scalar'),('htp','cpu')]:
 a=arrays[out];b=arrays[ref];den=sum(v*v for v in b);assert den>0 and math.isfinite(den)
 d=[abs(x-y) for x,y in zip(a,b)];viol=[i for i in range(2048) if d[i]>0.02+0.05*abs(b[i])]
 m={'nmse':sum(x*x for x in d)/den,'max_abs':max(d),'legacy_violations':len(viol),'nd':2048,'nf':0};metrics[out+'_'+ref]=m;print('PAIR',out,ref,m)
 with (O/f'{out}-{ref}-violators.csv').open('w',newline='') as f:
  w=csv.writer(f);w.writerow(['b','n','out','ref','abs_error','limit','excess'])
  for n in sorted(viol,key=lambda n:d[n]-(.02+.05*abs(b[n])),reverse=True):w.writerow([0,n,a[n],b[n],d[n],.02+.05*abs(b[n]),d[n]-(.02+.05*abs(b[n]))])
sel={}
for m in re.finditer(r'R39 SEL: attempt=(\S+) K=(\d+) N=(\d+) B=(\d+) type=(\S+)',(R/'r39-capture-full.txt').read_text()):
 sel.setdefault(m.group(1),set()).add(tuple(m.groups()[1:]))
print('ATTEMPT_ID_COLLISIONS',[(a,len(v)) for a,v in sel.items() if len(v)>1])
(O/'results.json').write_text(json.dumps({'sizes':sizes,'hashes':checks,'metrics':metrics,'attempt_descriptors':{a:sorted(v) for a,v in sel.items()},'native_run_exit':p.returncode,'scope':'offline reference recomputation on delivered bytes; no probe/candidate/device changes'},indent=2))
print('SCOPE: all refs computed locally; no new device capture.')
