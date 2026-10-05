#!/usr/bin/env python3
"""Gera a fixture MINIMAL deterministica do contrato de pacotes nativos.

Uso: python scripts/gen-native-deps-fixture.py

Saida: app/src/test/resources/native-deps/contract-min.zip

O ZIP representa a ESTRUTURA do pacote real (entries lib/ + models/ +
manifest.json) com tamanhos pequenos e conteudo deterministico. Usado pelo
NativeDepsContractFixtureTest (unit, roda em checkout limpo sem os ZIPs
reais de 50 MB). Os ZIPs REAIS v10 sao validados pelo
NativeDepsOfficialFixturesTest (rodado no momento da release).

Determinismo: timestamps fixos + conteudo fixo -> o ZIP gerado tem SHA-256
estavel. NAO alterar o conteudo sem atualizar os valores esperados do teste
contract-min (o teste verifica os tamanhos por nome).
"""
import io
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/native-deps/contract-min.zip"

# Conteudo deterministico: (nome no ZIP, bytes, nivel deflate)
ENTRIES = [
    ("manifest.json", b'{"fixture":"contract","version":1}', zipfile.ZIP_DEFLATED),
    ("lib/libalpha.so", bytes([0x41]) * 1000, zipfile.ZIP_DEFLATED),   # 'A' x1000
    ("lib/libbeta.so", bytes([0x42]) * 2000, zipfile.ZIP_DEFLATED),    # 'B' x2000
    ("models/tiny.bin", bytes(range(256)) * 2, zipfile.ZIP_STORED),    # 512 B STORED
]


def build() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for nome, dados, metodo in ENTRIES:
            info = zipfile.ZipInfo(nome, date_time=(2026, 10, 5, 12, 0, 0))
            info.compress_type = metodo
            info.external_attr = 0o100644 << 16
            z.writestr(info, dados)
    return buf.getvalue()


def main() -> None:
    dados = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(dados)
    print(f"ok: {OUT} ({len(dados)} bytes, sha256 {hashlib.sha256(dados).hexdigest()})")


if __name__ == "__main__":
    import hashlib  # noqa: E402  (apos definicao para clareza)
    main()
