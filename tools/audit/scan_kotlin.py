#!/usr/bin/env python3
"""Varredura estatica do codigo Kotlin/Java do SIG Android.

Findings:
 A. import nao usado (identificador nunca aparece no corpo)
 B. import duplicado / wildcard / alias problematico
 C. referencia a classe por STRING (Class.forName / setClassName / android:name)
    que pode apontar para classe removida (o compilador NAO checa)
 D. R.id usado em arquivo cujo layout declarante nao aparece no arquivo
    (classe do bug: findViewById de outro layout -> NPE em runtime)
 E. declaracoes de topo nunca referenciadas no repo (codigo morto)
"""
import os, re, sys, json, pathlib, collections

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "app" / "src"
KT_ROOTS = [
    SRC / "main/java/br/gov/sp/pcsp/launcher",
    SRC / "debug/java/br/gov/sp/pcsp/launcher",
    SRC / "test/java/br/gov/sp/pcsp/launcher",
]
JAVA_ROOT = SRC / "main/java"

CODE_EXTS = {".kt", ".java"}
REF_EXTS = {".kt", ".java", ".xml", ".ps1", ".json", ".gradle", ".md"}


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

def read(p):
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except Exception:
        return ""


def kt_sources():
    files = []
    for r in KT_ROOTS:
        if not r.exists():
            continue
        for p in sorted(r.rglob("*.kt")):
            files.append(p)
    return files


IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.M)
PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.M)

findings = collections.defaultdict(list)
stats = {}


def analyze_imports():
    """A + B: imports nao usados / duplicados / wildcard (Kotlin e Java)."""
    files = kt_sources() + sorted((JAVA_ROOT / "com").rglob("*.java"))
    for p in files:
        raw = read(p)
        lang = "java" if p.suffix == ".java" else "kt"
        imports = IMPORT_RE.findall(raw)
        if not imports:
            continue
        body_lines = []
        for line in raw.splitlines():
            if re.match(r"^\s*import\s+", line) or re.match(r"^\s*package\s+", line):
                continue
            body_lines.append(line)
        body = strip("\n".join(body_lines))
        # para deteccao por nome simples tambem olhamos KDoc/labels? nao: comentario nao conta
        seen = {}
        for idx, (fq, alias) in enumerate(imports):
            if fq.endswith(".*"):
                findings["B_wildcard_import"].append(f"{p.relative_to(ROOT)}: {fq}")
                continue
            name = alias or fq.split(".")[-1]
            if fq in seen:
                findings["B_import_duplicado"].append(
                    f"{p.relative_to(ROOT)}: {fq} (linhas repetidas)")
            seen[fq] = idx
            if not re.search(r"\b" + re.escape(name) + r"\b", body):
                findings["A_import_nao_usado"].append(f"{p.relative_to(ROOT)}: {fq}")


DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|private|protected|internal|open|abstract|sealed|data|enum|annotation|"
    r"value|inline|suspend|operator|infix|external|tailrec|const|lateinit|expect|actual|"
    r"override|fun|companion|vararg|noinline|crossinline|reified|out|in)\s+)*"
    r"(class|object|interface|typealias|enum\s+class|data\s+class|fun)\s+"
    r"(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?([A-Za-z_]\w*)",
    re.M)


def collect_declarations():
    """E: declaracoes de topo (coluna 0) de main+debug (test = referencias)."""
    decls = {}
    for p in kt_sources():
        if "/test/" in str(p).replace("\\", "/"):
            continue
        raw = read(p)
        body = strip(raw)
        for m in DECL_RE.finditer(body):
            line = body[:m.start()].count("\n") + 1
            kind, name = m.group(1), m.group(2)
            decls.setdefault(name, []).append((str(p.relative_to(ROOT)), line, kind))
    return decls


def count_references():
    """Conta ocorrencias de cada identificador em todo o repo (codigo+xml+scripts)."""
    counter = collections.Counter()
    for p in kt_sources():
        txt = strip(read(p))
        for w in re.findall(r"[A-Za-z_]\w*", txt):
            counter[w] += 1
    for ext in (".xml",):
        for p in (SRC / "main").rglob("*" + ext):
            txt = strip(read(p))
            for w in re.findall(r"[A-Za-z_]\w*", txt):
                counter[w] += 1
    return counter


MANIFEST_NAMES = set()
for _m in [SRC / "main/AndroidManifest.xml", SRC / "debug/AndroidManifest.xml"]:
    MANIFEST_NAMES |= set(re.findall(r'android:name="\.?([\w.]+)"', read(_m)))
MANIFEST_NAMES |= {n.split(".")[-1] for n in MANIFEST_NAMES}


def analyze_dead_code():
    decls = collect_declarations()
    counter = count_references()
    for name, sites in sorted(decls.items()):
        if name in MANIFEST_NAMES:
            continue
        if name.startswith("WHISPER_") or name in {"main"}:
            continue
        occ = counter.get(name, 0)
        if occ <= len(sites):
            # nao referenciado em lugar nenhum alem das proprias declaracoes
            findings["E_declaracao_orfao"].append(
                f"{name} :: " + "; ".join(f"{f}:{l} ({k})" for f, l, k in sites))


ID_DECL_RE = re.compile(r'@\+?id/([A-Za-z_]\w*)')
ID_REF_RE = re.compile(r'@\+?id/([A-Za-z_]\w*)')
R_ID_RE = re.compile(r'\bR\.id\.([A-Za-z_]\w*)')
R_LAYOUT_RE = re.compile(r'\bR\.layout\.([A-Za-z_]\w*)')


def analyze_ids():
    """D: R.id.X usado em arquivo que nao referencia o layout declarante de X."""
    layout_ids = collections.defaultdict(set)   # id -> {layouts}
    for p in (SRC / "main/res").rglob("*.xml"):
        ids = set(ID_DECL_RE.findall(read(p)))
        if ids:
            for i in ids:
                layout_ids[i].add(p.stem)
    for p in kt_sources():
        if "/test/" in str(p).replace("\\", "/"):
            continue
        raw = strip(read(p))
        used_ids = set(R_ID_RE.findall(raw))
        if not used_ids:
            continue
        own_layouts = set(R_LAYOUT_RE.findall(raw))
        for i in sorted(used_ids):
            owners = layout_ids.get(i, set())
            if len(owners) == 1 and not (owners & own_layouts):
                findings["D_id_de_outro_layout"].append(
                    f"{p.relative_to(ROOT)}: R.id.{i} pertence a layout '{list(owners)[0]}'"
                    f" (layouts no arquivo: {sorted(own_layouts) or 'nenhum'})")


CLASS_STRING_RE = re.compile(
    r'(?:Class\.forName|setClassName|loadClass|ComponentName)\s*\(?[^)\n]*?'
    r'"([\w.$]+)"')


def analyze_string_class_refs():
    """C: referencias a classe por string."""
    known = {p.stem for p in kt_sources()}
    for p in kt_sources():
        raw = read(p)
        for m in CLASS_STRING_RE.finditer(raw):
            cn = m.group(1)
            short = cn.split(".")[-1].replace("$", "")
            ok = short in known or ".launcher." in cn and short in known
            findings["C_ref_classe_por_string"].append(
                f"{p.relative_to(ROOT)}: \"{cn}\" -> " + ("OK" if ok else "CLASSE NAO ENCONTRADA"))


analyze_imports()
analyze_dead_code()
analyze_ids()
analyze_string_class_refs()

order = ["A_import_nao_usado", "B_wildcard_import", "B_import_duplicado",
         "C_ref_classe_por_string", "D_id_de_outro_layout", "E_declaracao_orfao"]
out = []
for k in order:
    v = findings.get(k, [])
    out.append(f"\n===== {k} ({len(v)}) =====")
    out.extend(sorted(v))
report = "\n".join(out)
print(report)
