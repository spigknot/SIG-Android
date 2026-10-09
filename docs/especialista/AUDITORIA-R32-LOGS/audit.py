from pathlib import Path
import hashlib, re, shutil, contextlib, io, json
ROOT=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada32')
OUT=Path(__file__).resolve().parent
names=['green','m1','m2','m3','m4','m5','m6']
rows=re.findall(r'(?m)^([0-9a-f]+)  (\S+)',(ROOT/'MANIFESTO-SHA256.txt').read_text())
checks=[(f,len(h)==64 and hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h) for h,f in rows if not f.startswith('relatorio:')]
print('MANIFEST',len(checks),'MATCH',sum(ok for _,ok in checks))
results=[]
for name in names:
 s=(ROOT/'export'/name/'run.out').read_text()
 n=int(re.search(r'TOTAL:\s*(\d+) RED',s)[1])
 original=(ROOT/('r32-'+name+'.out')).read_bytes()==(ROOT/'export'/name/'run.out').read_bytes()
 print('SCENARIO',name,'TOTAL_RED',n,'ROOT_OUTPUT_IDENTICAL',original)
 if name in ['green','m6']: print('\n'.join(l for l in s.splitlines() if 'T4' in l or 'T5' in l))
 results.append({'case':name,'red':n,'root_output_identical':original})
assert [r['red'] for r in results]==[0,4,1,1,1,1,2]
# Execute the real final Python export-check block on archived copies.
# The green adapter alone is replaced with the delivered no-guard m5 adapter.
WORK=OUT/'root'
for name in names:
 wd=WORK/'r32_pipe/export'/name;wd.mkdir(parents=True,exist_ok=True)
 for f in ['r29_settensor_adap.inc','r29_cores_gen.h']:shutil.copyfile(ROOT/'export'/name/f,wd/f)
p=WORK/'r32_pipe/export/green/r29_settensor_adap.inc'
shutil.copyfile(ROOT/'export/m5/r29_settensor_adap.inc',p)
assert 'if (offset != 0 || size !=' not in p.read_text()
script=(ROOT/'r32_pipeline.sh').read_text()
section=script[script.index('# teste do EXPORT:'):]
code=re.search(r"python3 - <<'PYEOF'\n(.*?)\nPYEOF",section,re.S)[1]
code=code.replace('/root/',WORK.as_posix()+'/')
b=io.StringIO()
with contextlib.redirect_stdout(b): exec(compile(code,'original-export-check-remapped','exec'),{})
(OUT/'stale-export-check.log').write_text(b.getvalue())
print('STALE_GREEN_NO_GUARDS_EXPORT_CHECK_EXIT',0,'STDOUT',b.getvalue().strip())
print('EXPORT_FIXTURE_MISSING',all(not (ROOT/'export'/name/'r32_fixture.c').exists() for name in names))
print('PIPELINE_BUILD_FAILURE_ACTION','continue' if 'head -n 3 build.err; continue' in script else 'other')
(OUT/'results.json').write_text(json.dumps({'manifest_matching':sum(ok for _,ok in checks),'scenarios':results,'stale_export_check_exit':0,'stale_export_check_stdout':b.getvalue()},indent=2))
assert all(ok for _,ok in checks)
print('NOTE: independent semantic check executed on scratch copies; no app/probe/candidate/device changes.')
