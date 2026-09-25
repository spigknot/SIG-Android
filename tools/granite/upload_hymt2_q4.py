"""Publica o Hy-MT2 Q4_0 (quantizado do Q8_0) no R2, pasta models/hymt2/.

Mesmo padrão de tools/granite/upload_gather_model.py: boto3 + release/r2_config.json,
bucket sig-android. Depois imprime URL, tamanho e SHA-256 para o manifesto do app.
"""
import json, boto3, pathlib, hashlib, sys, time

cfg = json.loads(pathlib.Path("D:/Projetos/SIG/release/r2_config.json").read_text(encoding="utf-8"))
bucket = cfg["bucket"]
public_base = cfg["public_base"].rstrip("/")
s3 = boto3.client(
    "s3",
    endpoint_url=cfg["endpoint"],
    aws_access_key_id=cfg["access_key_id"],
    aws_secret_access_key=cfg["secret_access_key"],
    region_name="auto",
)

local = pathlib.Path("D:/Projetos/modelos/Hy-MT2-1.8B-Q4_0.gguf")
key = "models/hymt2/Hy-MT2-1.8B-Q4_0.gguf"

print(f"subindo {local.name} ({local.stat().st_size/1e6:.1f} MB) -> {key}")
t0 = time.time()
with local.open("rb") as f:
    s3.put_object(Bucket=bucket, Key=key, Body=f, ContentType="application/octet-stream")
dt = time.time() - t0
print(f"upload OK em {dt:.0f}s")

head = s3.head_object(Bucket=bucket, Key=key)
print("ETag:", head["ETag"], "| size:", head["ContentLength"])

h = hashlib.sha256()
with local.open("rb") as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
print("SHA-256 local:", h.hexdigest())
print("URL:", f"{public_base}/{key}")
