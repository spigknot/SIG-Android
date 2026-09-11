#!/usr/bin/env python3
"""SIG — recursos/ids de XML: declarados sem uso e usados sem declaracao.

1. id declarado (@+id/x) em layout/menu e nunca referenciado (R.id.x, @id/x, tools)
2. layout/drawable/menu/anim declarado e nunca referenciado (R.layout.x / @layout/x)
3. android:id ausente em view com android:onClick? (n/a) / duplicidade de id no MESMO layout
4. ids duplicados entre layouts usados pela MESMA Activity (risco de findViewById errado)
"""
import re, subprocess, collections, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
KT = [ROOT / f for f in subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split()
      if f.endswith((".kt", ".java")) and f.startswith("app/src/") and "/cpp/" not in f]
R = collections.defaultdict(list)
def add(k, v): R[k].append(v)

ID_DECL = re.compile(r'@\+id/([A-Za-z_]\w*)')
ID_REF = re.compile(r'@id/([A-Za-z_]\w*)')
R_ID = re.compile(r'\bR\.id\.([A-Za-z_]\w*)')

decl_layout = collections.defaultdict(set)      # id -> arquivos que declaram
for p in sorted(RES.rglob("*.xml")):
    for i in ID_DECL.findall(p.read_text(encoding="utf-8", errors="replace")):
        decl_layout[i].add(p.relative_to(RES).as_posix())

refs = collections.Counter()
for p in KT:
    t = p.read_text(encoding="utf-8", errors="replace")
    for m in R_ID.finditer(t):
        refs[m.group(1)] += 1
for p in sorted(RES.rglob("*.xml")):
    t = p.read_text(encoding="utf-8", errors="replace")
    for m in ID_REF.finditer(t):
        # @id/x e tambem @+id/x (referencia + declaracao no mesmo lugar)
        refs[m.group(1)] += 1
    for m in ID_DECL.finditer(t):
        refs[m.group(1)] += 1
    if 'android:id="@+id/' in t:
        pass

for i, owners in sorted(decl_layout.items()):
    if refs[i] <= len(owners):     # so declaracoes, nenhuma referencia real
        add("1_id_declarado_sem_uso", f"{i} (em {', '.join(sorted(owners))})")

# duplicidade de id no MESMO arquivo
for p in sorted(RES.rglob("*.xml")):
    t = p.read_text(encoding="utf-8", errors="replace")
    c = collections.Counter(ID_DECL.findall(t))
    for i, n in c.items():
        if n > 1:
            add("3_id_duplicado_no_mesmo_arquivo", f"{p.relative_to(RES).as_posix()}: {i} x{n}")

# recursos de arquivo sem referencia
for kind in ("layout", "drawable", "menu", "anim", "xml", "raw", "mipmap", "font"):
    d = RES / kind
    if not d.is_dir():
        continue
    names = {p.stem for p in d.rglob("*") if p.is_file()}
    for n in sorted(names):
        pat = re.compile(r'(?:@' + kind + r'/|R\.' + kind + r'\.)' + re.escape(n) + r'\b')
        hit = False
        for p in KT:
            if pat.search(p.read_text(encoding="utf-8", errors="replace")):
                hit = True; break
        if not hit:
            for p in list(RES.rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]:
                if pat.search(p.read_text(encoding="utf-8", errors="replace")):
                    hit = True; break
        if not hit:
            add("2_recurso_sem_referencia", f"{kind}/{n}")

out = []
for k in sorted(R):
    out.append(f"\n===== {k} ({len(R[k])}) =====")
    out.extend(sorted(set(R[k])))
rep = "\n".join(out) or "(sem findings)"
print(rep)
