import os, sys, json, hashlib, subprocess, stat, time, collections
from pathlib import Path
OUT=Path('D:/Projetos/SIG/docs/especialista')
M=json.loads((OUT/'RELATORIO-LIMPEZA-SIG-READONLY.json').read_text(encoding='utf-8'))
PROTECTED=['D:/Projetos/SIG/docs/especialista','D:/Projetos/SIG/docs/npu-hymt2','C:/llama-npu']
def norm(p): return os.path.abspath(str(p)).replace('\\','/').casefold()
def under(p,r): return norm(p)==norm(r) or norm(p).startswith(norm(r)+'/')
def ext(p): return '\\\\?\\'+os.path.abspath(str(p))
def linked(p):
    for x in [p,*p.parents]:
        try:
            if os.lstat(ext(x)).st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT: return True
        except FileNotFoundError: pass
    return False
def digest(p):
    h=hashlib.sha256()
    with open(ext(p),'rb') as f:
        for b in iter(lambda:f.read(4*1024*1024),b''): h.update(b)
    return h.hexdigest()
def git(root,*args):
    q=subprocess.run(['git','-C',str(root),*args],capture_output=True)
    return q.returncode,q.stdout.decode('utf-8','replace')
def snapshot(root):
    result={}
    if not Path(root).exists(): return result
    for base,dirs,files in os.walk(root,followlinks=False):
        dirs[:]=[d for d in dirs if not linked(Path(base)/d)]
        for f in files:
            p=Path(base)/f
            if linked(p): continue
            if any(s in f.casefold() for s in ['credential','secret','keystore','.env','token','password']):
                result[str(p)]=['metadata-only',os.stat(ext(p)).st_size,os.stat(ext(p)).st_mtime_ns]; continue
            result[str(p)]=[os.stat(ext(p)).st_size,digest(p)]
    return result
roots=[i['root'] for i in M['inventory']]
repo_roots=['D:/Projetos/SIG','D:/svr11','D:/ocl1','D:/svr-clean']
tracked=set(); statuses={}
for root in repo_roots:
    rc,s=git(root,'status','--porcelain=v1','-uall'); statuses[root]=s
    if rc: raise RuntimeError('git preflight failed '+root)
    rc,s=git(root,'ls-files','-z')
    if rc: raise RuntimeError('tracked lookup failed')
    tracked.update(norm(Path(root)/p) for p in s.split('\x00') if p)
ps=subprocess.run(['powershell.exe','-NoProfile','-Command','Get-CimInstance Win32_Process | Select-Object ProcessId,Name,ExecutablePath,CommandLine | ConvertTo-Json -Compress'],capture_output=True,text=True)
if ps.returncode: raise RuntimeError('process check failed')
processes=json.loads(ps.stdout); hits=[]; active=set()
for proc in processes:
    if proc.get('ProcessId')==os.getpid() or proc.get('Name','').lower()=='powershell.exe': continue
    text=((proc.get('ExecutablePath') or '')+' '+(proc.get('CommandLine') or '')).replace('\\','/').casefold()
    for root in roots:
        if norm(root) in text:
            active.add(norm(root)); hits.append({'pid':proc['ProcessId'],'name':proc['Name'],'root':root})
# Only exact manifest entries; no recursive deletion.
candidates=[]
for e in M['allowlist_A_exact_files']: candidates.append(dict(e,approval='A'))
for e in M['conditional_C_generated_files']:
    p=e['path'].casefold()
    if any(s in p for s in ['/app/.cxx/','/app/build/','/native-dependencies/build/','/build/']): candidates.append(dict(e,approval='C-generated-reviewed'))
for e in M['models_graphs_C_preserve']:
    p=e['path'].casefold()
    if any(s in p for s in ['/executionhistory/','/lint-cache/','/logs-rodada15/','/logs-rodada16/','/logs-rodada18/']) or p.endswith('/mulmm_probe_diag.bin'):
        candidates.append(dict(e,approval='C-old-cache-or-diagnostic'))
plan={norm(e['path']):e for e in candidates}
(OUT/'LIMPEZA-PLANO-EXECUCAO.json').write_text(json.dumps({'candidates':list(plan.values()),'process_hits':hits,'git_before':statuses},ensure_ascii=False,indent=2),encoding='utf-8')
before={r:snapshot(r) for r in PROTECTED}
records=[]; groups=collections.Counter(); directories=set()
journal=OUT/'LIMPEZA-JOURNAL.jsonl'
with journal.open('w',encoding='utf-8') as log:
    for i,e in enumerate(plan.values()):
        p=Path(e['path']); rec={'path':str(p),'approval':e['approval'],'bytes':e['bytes']}
        try:
            if any(under(p,r) for r in PROTECTED): raise ValueError('protected')
            if not any(under(p,r) for r in roots): raise ValueError('outside-approved-roots')
            if any(under(p,r) for r in active): raise ValueError('active-process-root')
            if norm(p) in tracked: raise ValueError('tracked')
            low=norm(p)
            if any(s in low for s in ['/.git/','/src/test/','/src/androidtest/','/fixtures/','/qairt/','/sdk/']): raise ValueError('essential-or-sdk')
            if p.suffix.lower() in ['.zip','.gguf','.onnx','.qnn','.tar']: raise ValueError('production-package-or-uncertain-model')
            if linked(p): raise ValueError('reparse-path')
            st=os.stat(ext(p))
            if not stat.S_ISREG(st.st_mode) or st.st_size!=e['bytes']: raise ValueError('size-or-type-changed')
            h=digest(p); check=os.stat(ext(p))
            if (check.st_size,check.st_mtime_ns)!=(st.st_size,st.st_mtime_ns): raise ValueError('changed-during-hash')
            rec['sha256']=h
            # Journal durable intent before deletion; verify exact target afterwards.
            log.write(json.dumps(dict(rec,status='intent'))+'\n'); log.flush()
            os.unlink(ext(p))
            if os.path.lexists(ext(p)): raise RuntimeError('still-present-after-unlink')
            rec['status']='deleted'; directories.add(p.parent)
            root=max((r for r in roots if under(p,r)),key=len); groups[root]+=st.st_size
        except FileNotFoundError: rec['status']='already-absent'
        except ValueError as ex: rec['status']='skipped'; rec['reason']=str(ex)
        except Exception as ex: rec['status']='error'; rec['reason']=str(ex)
        records.append(rec); log.write(json.dumps(rec,ensure_ascii=False)+'\n')
        if i%250==0:
            log.flush(); (OUT/'LIMPEZA-CHECKPOINT.json').write_text(json.dumps({'processed':i+1,'counts':dict(collections.Counter(x['status'] for x in records))}),encoding='utf-8')
# Remove empty leaf build directories only, never source or entire worktree roots.
empty=0
for p in sorted(directories,key=lambda p:len(str(p)),reverse=True):
    if any(s in norm(p) for s in ['/app/build/','/app/.cxx/','/native-dependencies/build/']) and not linked(p):
        try: os.rmdir(ext(p)); empty+=1
        except OSError: pass
verification=[]
for r,files in before.items():
    changed=[]
    for p,value in files.items():
        try:
            if value[0]=='metadata-only':
                st=os.stat(ext(p)); now=['metadata-only',st.st_size,st.st_mtime_ns]
            else: now=[os.stat(ext(p)).st_size,digest(p)]
            if now!=value: changed.append(p)
        except OSError: changed.append(p)
    verification.append({'root':r,'baseline_files':len(files),'changed_or_missing':changed})
after={r:git(r,'status','--porcelain=v1','-uall')[1] for r in repo_roots}
removed=[x for x in records if x['status']=='deleted']
remaining=[x['path'] for x in removed if os.path.lexists(ext(x['path']))]
report={'manifest':str(OUT/'RELATORIO-LIMPEZA-SIG-READONLY.json'),'logical_bytes_removed':sum(x['bytes'] for x in removed),'deleted_files':len(removed),'status_counts':dict(collections.Counter(x['status'] for x in records)),'path_groups_bytes':dict(groups),'empty_leaf_dirs_removed':empty,'removed_targets_still_present':remaining,'protected_verification':verification,'git_before':statuses,'git_after':after,'git_status_unchanged':statuses==after,'process_hits':hits,'records':records,'models_preserved':[e for e in M['models_graphs_C_preserve'] if norm(e['path']) not in plan],'worktree_leftovers':'Registered worktrees preserved; no force removal, unique sources untouched. ZIP packages preserved pending provenance/fixture review.'}
(OUT/'RELATORIO-LIMPEZA-EXECUTADA.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
summary={k:v for k,v in report.items() if k not in ['records','git_before','git_after']}
(OUT/'RELATORIO-LIMPEZA-EXECUTADA.txt').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
