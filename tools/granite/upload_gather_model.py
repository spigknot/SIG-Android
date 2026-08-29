"""Publica o modelo Granite FP16 (convertido Slice->Gather) no R2.

Só o .onnx muda (o .data externo é o mesmo já publicado).
"""
import json, boto3, pathlib, hashlib, sys, time

cfg = json.loads(pathlib.Path("D:/Projetos/SIG/release/r2_config.json").read_text(encoding="utf-8"))
# ⚠️ O SIG Android usa SOMENTE o bucket "sig-android" (public_base pub-6476622...).
# O release/r2_config.json deste projeto já traz bucket/public_base corretos.
# NUNCA usar o r2_config.json do SIG Windows (bucket "sig" = outro projeto).
bucket = cfg["bucket"]
public_base = cfg["public_base"].rstrip("/")
s3 = boto3.client(
    "s3",
    endpoint_url=cfg["endpoint"],
    aws_access_key_id=cfg["access_key_id"],
    aws_secret_access_key=cfg["secret_access_key"],
    region_name="auto",
)

local = pathlib.Path("D:/Projetos/SIG/tools/granite/package/granite-5.0-turboctc-fp16-gather.onnx")
key = "models/granite/5.0-turbo/granite-5.0-turboctc-fp16-gather.onnx"

print(f"subindo {local.name} ({local.stat().st_size/1e6:.1f} MB) -> {key}")
t0 = time.time()
with local.open("rb") as f:
    s3.put_object(Bucket=bucket, Key=key, Body=f, ContentType="application/octet-stream")
dt = time.time() - t0
print(f"upload OK em {dt:.0f}s")

# verifica
head = s3.head_object(Bucket=bucket, Key=key)
print("ETag:", head["ETag"], "| size:", head["ContentLength"])

# sha256 local
h = hashlib.sha256()
with local.open("rb") as f:
    for chunk in iter(lambda: f.read(1024*1024), b""):
        h.update(chunk)
print("SHA-256 local:", h.hexdigest())
print("URL:", f"{public_base}/{key}")
