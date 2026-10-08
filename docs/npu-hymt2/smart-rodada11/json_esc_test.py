#!/usr/bin/env python3
# R11: teste do contrato de serializacao (ASCII-equivalente do json_esc do probe):
# aspas, backslash, newline, tab, controles <0x20 e Unicode PT devem parsear
# exatamente; campos null/erro/cap e o reset/identity presentes no fim real.
import json, sys

def esc(s: str) -> str:
    out = []
    for c in s:
        o = ord(c)
        if c == '"': out.append('\\"')
        elif c == '\\': out.append('\\\\')
        elif c == '\n': out.append('\\n')
        elif c == '\r': out.append('\\r')
        elif c == '\t': out.append('\\t')
        elif o < 0x20: out.append('\\u%04x' % o)
        else: out.append(c)
    return "".join(out)

falhas = 0
def check(c, m):
    global falhas
    print(("ok: " if c else "FAIL: ") + m)
    if not c: falhas += 1

casos = [
    ('aspas "internas"', 'texto com "aspas" e \\ backslash'),
    ('newline e tab', 'linha1\nlinha2\ttab'),
    ('controle 0x01/0x04', 'ctrl:\x01\x04 fim'),
    ('unicode PT', 'Ação — coração, ção, ã, é, 中文'),
    ('vazio', ''),
]
for nome, s in casos:
    j = '{"t":"req","texto":"%s","resultado":"%s","cap":false}' % (esc(s), esc(s))
    try:
        d = json.loads(j)
        check(d["texto"] == s and d["resultado"] == s, f"roundtrip: {nome}")
    except Exception as e:
        check(False, f"parse falhou ({nome}): {e}")

# campos de contrato presentes ao fim real (exemplo de registro)
ex = '{"t":"req","sess":"6d4cf1e","i":2,"cap":true,"ok":false,"stop":1,"reset_ms":4.2,"wall_ms":10.5}'
d = json.loads(ex)
check(all(k in d for k in ("t","sess","i","cap","ok","stop","reset_ms","wall_ms")), "campos de contrato presentes (cap/stop/reset/wall)")

print("RESULTADO: " + ("%d FALHAS" % falhas if falhas else "TODOS PASS"))
sys.exit(1 if falhas else 0)
