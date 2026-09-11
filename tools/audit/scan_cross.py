#!/usr/bin/env python3
"""SIG Android — cross-checks de referencias (pos-reorganizacao). v3"""
import re, subprocess, collections, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
APPJ = ROOT / "app/src"

def tracked():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout
    return [ROOT / l for l in out.splitlines() if l]
TRACKED = tracked()
def rel(p): return str(p).replace("\\", "/").split("/SIG/")[-1]

def is_app(p):
    r = rel(p)
    return r.startswith("app/src/") and "/cpp/" not in r

KT_ALL = [p for p in TRACKED if p.suffix == ".kt" and is_app(p)]
MAIN_KT = [p for p in KT_ALL if "/main/java/" in rel(p)]
TEST_KT = [p for p in KT_ALL if "Test" in p.stem]
DEBUG_KT = [p for p in KT_ALL if "/debug/java/" in rel(p)]
JAVA = [p for p in TRACKED if p.suffix == ".java" and is_app(p)]

def strip(text):
    """Remove comentarios e conteudo de strings (guarda ${...}); preserva nº de linhas."""
    out, i, n = [], 0, len(text)
    prev = ""
    while i < n:
        if text.startswith("//", i):
            while i < n and text[i] != "\n": i += 1
            continue
        if text.startswith("/*", i):
            d, i = 1, i + 2
            start = i
            while i < n and d:
                if text.startswith("/*", i): d, i = d + 1, i + 2
                elif text.startswith("*/", i): d, i = d - 1, i + 2
                else: i += 1
            out.append("\n" * text.count("\n", start, i)); prev = " "; continue
        c = text[i]
        if c == "`":
            i += 1
            while i < n and text[i] != "`": i += 1
            i += 1; out.append("`x`"); prev = "x"; continue
        if c == "'" and not (prev.isalnum() or prev in "_."):
            j = i + 1
            while j < n:
                if text[j] == "\\": j += 2; continue
                if text[j] == "'": j += 1; break
                if text[j] == "\n": break
                j += 1
            out.append("'x'"); i = j; prev = "x"; continue
        if c == '"':
            triple = text.startswith('"""', i)
            i += 3 if triple else 1
            while i < n:
                if not triple and text[i] == "\\": i += 2; continue
                if triple and text.startswith('"""', i): i += 3; break
                if not triple and text[i] == '"': i += 1; break
                if text.startswith("${", i):
                    j = text.find("}", i)
                    if j < 0: break
                    out.append(text[i:j + 1]); i = j + 1; continue
                i += 1
            out.append('""'); prev = '"'; continue
        out.append(c); prev = c; i += 1
    return "".join(out)

BODY = {p: strip(p.read_text(encoding="utf-8", errors="replace")) for p in KT_ALL + JAVA}
ALLCODE = "\n".join(BODY.values())
R = collections.defaultdict(list)
def add(k, v): R[k].append(v)
DIAG = [f"kt={len(KT_ALL)} main={len(MAIN_KT)} test={len(TEST_KT)} debug={len(DEBUG_KT)} java={len(JAVA)}"]

SEAMS = ["SttResponseParsers", "TranscriptionReport", "SttAudioProbe", "LittleEndianIo",
         "GraniteBinarySupport", "MediaUriSupport", "MediaTypeRules"]
topfun = re.compile(r'^[ \t]*(?:internal\s+|public\s+|private\s+)?(?:suspend\s+|inline\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*[(<]', re.M)
memberfun = re.compile(r'^[ \t]+(?:(?:private|internal|protected|public|open|override|suspend|inline|operator|infix|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*[(<]', re.M)

for s in SEAMS:
    path = next((p for p in KT_ALL if p.stem == s), None)
    if not path:
        add("1_seam_ausente", s); continue
    names = sorted(set(topfun.findall(BODY[path])))
    others = "\n".join(v for k, v in BODY.items() if k != path and k.stem != s + "Test")
    onlytest = "\n".join(v for k, v in BODY.items() if k.stem == s + "Test")
    DIAG.append(f"{s}: {len(names)} funcoes de topo")
    for n in names:
        if not re.search(r"\b" + re.escape(n) + r"\b", others):
            if re.search(r"\b" + re.escape(n) + r"\b", onlytest):
                add("1b_seam_so_usada_pelo_teste", f"{s}.{n}")
            else:
                add("1a_seam_sem_consumidor_nenhum", f"{s}.{n}")

seamfn = set()
for s in SEAMS:
    p = next((p for p in KT_ALL if p.stem == s), None)
    if p: seamfn |= set(topfun.findall(BODY[p]))
for p in MAIN_KT:
    if p.stem in SEAMS: continue
    for n in sorted(set(memberfun.findall(BODY[p])) & seamfn):
        add("2_nome_de_seam_redefinido", f"{p.stem}.{n}")

DECL_RE = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:(?:private|internal|protected|public|open|abstract|sealed|data|value|inline|suspend|'
    r'operator|infix|external|tailrec|const|lateinit|expect|actual|override|companion|noinline|'
    r'crossinline|reified|out|in)\s+)*'
    r'(class|object|interface|enum\s+class|data\s+class|fun)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?([A-Za-z_]\w*)',
    re.M)
OTHER_TXT = {}
for p in TRACKED:
    if p.suffix in (".xml", ".ps1", ".py", ".md", ".json", ".gradle", ".pro", ".txt"):
        try: OTHER_TXT[p] = p.read_text(encoding="utf-8", errors="replace")
        except Exception: pass
BASELINE = ALLCODE + "\n" + "\n".join(OTHER_TXT.values())

decls = collections.defaultdict(list)
for p in KT_ALL:
    if p.stem.endswith("Test"): continue
    lines = BODY[p].splitlines()
    for m in DECL_RE.finditer(BODY[p]):
        ln = BODY[p][:m.start()].count("\n") + 1
        ctx = " ".join(lines[max(0, ln - 4):ln])
        if re.search(r"\boverride\b|@JvmStatic|@JvmName|@JvmField|@Test|@Before|@After|\bexternal\b", ctx): continue
        decls[m.group(2)].append((p.stem, ln, m.group(1)))
SKIP = {"main", "invoke", "equals", "hashCode", "toString", "compareTo", "iterator",
        "component1", "values", "entries", "valueOf", "onCreate", "onResume", "onPause"}
for name, sites in sorted(decls.items()):
    if name in SKIP or len(name) <= 3: continue
    occ = len(re.findall(r"\b" + re.escape(name) + r"\b", BASELINE))
    if occ <= len(sites):
        add("3_sem_referencia_no_repo",
            f"{name} :: " + "; ".join(f"{f}:{l}" for f, l, _ in sites))

app_files = [p for p in TRACKED if is_app(p) and p.suffix in (".xml", ".kt", ".java", ".ps1", ".gradle")]
RES_DIR = APPJ / "main/res"
defined = collections.defaultdict(set)
for p in RES_DIR.rglob("*"):
    if not p.is_file(): continue
    parts = p.relative_to(RES_DIR).parts
    if parts[0].startswith("values"):
        if p.suffix != ".xml": continue
        t = p.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'<(string|color|dimen|bool|integer|style|array|plurals|string-array|integer-array|attr|id)\s+name="([^"]+)"', t):
            defined[m.group(1)].add(m.group(2))
    else:
        defined[parts[0].split("-")[0]].add(p.stem)
REF_RE = re.compile(r'@(android:)?(string|color|drawable|layout|menu|anim|raw|xml|font|mipmap|dimen|style|bool|integer|array)/([A-Za-z_][\w.]*)')
RK_RE = re.compile(r'(?<!android\.)\bR\.(string|color|drawable|layout|menu|anim|raw|xml|font|mipmap|dimen|style|bool|integer|array)\.([A-Za-z_]\w*)')
refs = collections.defaultdict(set)
for p in app_files:
    t = p.read_text(encoding="utf-8", errors="replace")
    for m in REF_RE.finditer(t):
        if m.group(1): continue          # @android:... = recurso da plataforma
        refs[m.group(2)].add((m.group(3), rel(p)))
    if p.suffix in (".kt", ".java") and p in BODY:
        for m in RK_RE.finditer(BODY[p]):
            refs[m.group(1)].add((m.group(2), rel(p)))
for typ, items in sorted(refs.items()):
    for name, where in sorted(items):
        if name not in defined.get(typ, set()):
            add("4_recurso_referenciado_sem_definicao", f"{typ}/{name} -> {where}")
for typ in ("string", "color", "dimen", "bool", "integer", "array", "font", "raw", "xml", "anim", "menu", "mipmap", "drawable", "layout", "style"):
    used = {n for n, _ in refs.get(typ, set())}
    for n in sorted(defined.get(typ, set()) - used):
        if not re.search(r"\b" + re.escape(n) + r"\b", ALLCODE):
            add("5_recurso_definido_sem_referencia", f"{typ}/{n}")

GONE = ["NpuTestActivity", "NpuPackageManager", "NpuDiagnostics", "NpuNativeProbe",
        "NpuModelManifest", "NpuPackageDownloader", "npu_model_manifest", "ic_tool_npu",
        "tool_card_npu_dashed_bg", "activity_npu_test", "launcher.experimental"]
for p in TRACKED:
    if not is_app(p) and not rel(p).startswith(("scripts/", "tools/", "docs/", "app/build.gradle")): continue
    try: t = p.read_text(encoding="utf-8", errors="replace")
    except Exception: continue
    for g in GONE:
        if g in t:
            add("6_simbolo_removido_ainda_citado", f"{g} -> {rel(p)}")

out = ["DIAG: " + " | ".join(DIAG)]
for k in sorted(R):
    out.append(f"\n===== {k} ({len(R[k])}) =====")
    out.extend(sorted(set(R[k])))
rep = "\n".join(out)
print(rep)
