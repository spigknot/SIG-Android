from pathlib import Path
import re, json, hashlib, collections, runpy, contextlib, io
R=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada37');O=Path(__file__).resolve().parent
text=(R/'r37-capture-full.txt').read_text(); rows={};conflicts=[]
pat=r'NpuProbe\(\s*(\d+)\): MM\[K=(\d+) N=(\d+) B=(\d+) ([^\]]+)\] (.*)'
for m in re.finditer(pat,text):
 k=tuple(m.groups()[:5]);v=m.group(6)
 if k in rows and rows[k]!=v:conflicts.append(k)
 rows[k]=v
assert not conflicts,conflicts
structured=[]
for k,v in rows.items():
 fields=dict(re.findall(r'(hrc|pre|post|nf_ref|nf_out|nd|max_abs|nrmse|nmse_pin|viol)=([^ ]+)',v))
 nd,expected=map(int,fields['nd'].split('/'));assert nd==expected and fields['nf_ref']==fields['nf_out']=='0' and fields['hrc']=='0' and fields['post']=='1',(k,v)
 structured.append({'pid':int(k[0]),'K':int(k[1]),'N':int(k[2]),'B':int(k[3]),'type':k[4],**fields,'legacy_printed':v.split()[-1]})
checks=[]
for h,f in re.findall(r'(?m)^([0-9a-f]{64})\s+(\S+)',(R/'MANIFESTO-SHA256.txt').read_text()):
 if not f.startswith('relatorio:'):checks.append({'file':f,'match':hashlib.sha256((R/f).read_bytes()).hexdigest()==h})
assert len(checks)==6 and all(c['match'] for c in checks)
print('MANIFEST 6/6; UNIQUE_RESULTS',len(rows),'PIDS',dict(collections.Counter(x['pid'] for x in structured)))
print('REAL_SHAPES',sorted({(x['K'],x['N'],x['B']) for x in structured if x['K']!=256}))
for x in sorted(structured,key=lambda x:(x['pid'],x['K'],x['N'],x['B'],x['type'])):
 if x['K']!=256 and x['type'] in ['q4_K','q6_K']: print('KQUANT_REAL',x['K'],x['N'],x['B'],x['type'],'nd='+x['nd'],'NMSE='+x['nmse_pin'],'viol='+x['viol'],x['legacy_printed'])
print('OPSTAGE_DIAG_FAIL_EVENTS',text.count('OPSTAGE_DIAG_FAIL'))
model=Path('D:/Projetos/SIG/docs/vulkan-hymt2-fusion-round/logs-rodada13/hymt2_app.gguf')
result={'checks':checks,'structured_results':structured,'conflicts':conflicts}
if model.is_file():
 h=hashlib.sha256()
 with model.open('rb') as f:
  for chunk in iter(lambda:f.read(4*1024*1024),b''):h.update(chunk)
 print('MODEL',model.stat().st_size,h.hexdigest());result['model_sha256']=h.hexdigest();result['model_size']=model.stat().st_size
 buf=io.StringIO()
 with contextlib.redirect_stdout(buf): ns=runpy.run_path(str(R/'r37_gguf_invent.py'))
 (O/'inventory-run.log').write_text(buf.getvalue(),encoding='utf-8')
 delivered_lines=(R/'r37-inventario-gguf.txt').read_text().splitlines()
 assert delivered_lines[-1].startswith(h.hexdigest()+' *'), 'model hash suffix differs'
 assert buf.getvalue().splitlines()==delivered_lines[:-1], 'local inventory differs beyond appended SHA row'
 ts=ns['tens'];counts=collections.Counter(t for n,t,d in ts if len(d)==2)
 unique=collections.Counter((t,*d) for n,t,d in ts if len(d)==2)
 print('INVENTORY_MATCH',len(ts),'2D_COUNTS',dict(counts))
 for key,n in sorted(unique.items()):print('SHAPE_TENSOR_COUNT',*key,n)
 print('NON_BLOCK_2D',[(n,t,d) for n,t,d in ts if len(d)==2 and not n.startswith('blk.')])
 result['inventory_tensor_count']=len(ts);result['rank2_count']=dict(counts);result['unique_shapes']=[{'type':key[0],'K':key[1],'N':key[2],'count':n}for key,n in sorted(unique.items())]
 result['non_block_rank2']=[{'name':n,'type':t,'dims':d}for n,t,d in ts if len(d)==2 and not n.startswith('blk.')]
else:print('MODEL_FILE_UNAVAILABLE; inventory not independently reexecuted')
(O/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('NO_APP_OR_DEVICE_CHANGED')
