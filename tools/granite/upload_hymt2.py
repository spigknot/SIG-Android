"""Publica um Hy-MT2 GGUF local no R2 (pasta models/hymt2/).

Uso: python upload_hymt2.py <arquivo_local.gguf> [nome_no_r2.gguf]

Sem argumentos, so mostra os .gguf disponiveis em D:/Projetos/modelos/ com
seu tamanho (util para planejar a curva de quantizacoes).
"""
import boto3
import hashlib
import json
import pathlib
import sys
import time

MODELOS = pathlib.Path("D:/Projetos/modelos")
R2_CFG = pathlib.Path("D:/Projetos/SIG/release/r2_config.json")


def mostrar_disponiveis():
    print("Hy-MT2 .gguf em D:/Projetos/modelos/:")
    for g in sorted(MODELOS.glob("*Hy-MT2*.gguf")):
        print(f"  {g.name:44} {g.stat().st_size / 1048576:8.0f} MB")
    return 1


def main() -> int:
    if len(sys.argv) < 2:
        return mostrar_disponiveis()
    local = pathlib.Path(sys.argv[1])
    if not local.is_file():
        print("nao existe:", local)
        return 2
    nome_r2 = sys.argv[2] if len(sys.argv) > 2 else local.name

    cfg = json.loads(R2_CFG.read_text(encoding="utf-8"))
    public_base = cfg["public_base"].rstrip("/")
    s3 = boto3.client(
        "s3",
        endpoint_url=cfg["endpoint"],
        aws_access_key_id=cfg["access_key_id"],
        aws_secret_access_key=cfg["secret_access_key"],
        region_name="auto",
    )

    key = f"models/hymt2/{nome_r2}"
    print(f"subindo {local.name} ({local.stat().st_size / 1e6:.1f} MB) -> {key}")
    t0 = time.time()
    with local.open("rb") as f:
        s3.put_object(Bucket=cfg["bucket"], Key=key, Body=f, ContentType="application/octet-stream")
    print(f"  upload OK em {time.time() - t0:.0f}s")

    h = hashlib.sha256()
    with local.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    print("  sha256:", h.hexdigest())
    print("  URL:", f"{public_base}/{key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
