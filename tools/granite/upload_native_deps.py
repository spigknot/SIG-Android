"""Publica os ZIPs do pacote nativo (sig-android-dependencies-v<ver>-<abi>.zip) no R2.

Padrão identico ao upload_gather_model.py: boto3 + release/r2_config.json,
bucket sig-android, chave na RAIZ do bucket (como nas URLs do
NativeDependencyManager.kt). Uso:

    python tools/granite/upload_native_deps.py <versao>

Depois confere tamanho de cada ZIP contra o packages.json gerado pelo
build-android-native-dependencies.ps1.
"""
import json, boto3, pathlib, hashlib, sys, time

version = sys.argv[1] if len(sys.argv) > 1 else "4"
cfg = json.loads(pathlib.Path("D:/Projetos/SIG/release/r2_config.json").read_text(encoding="utf-8"))
public_base = cfg["public_base"].rstrip("/")
s3 = boto3.client(
    "s3",
    endpoint_url=cfg["endpoint"],
    aws_access_key_id=cfg["access_key_id"],
    aws_secret_access_key=cfg["secret_access_key"],
    region_name="auto",
)

build_dir = pathlib.Path("D:/Projetos/SIG/native-dependencies/build")
for abi in ("arm64-v8a", "x86_64"):
    local = build_dir / f"sig-android-dependencies-v{version}-{abi}.zip"
    if not local.is_file():
        print(f"AUSENTE: {local}")
        sys.exit(1)
    key = local.name
    print(f"subindo {local.name} ({local.stat().st_size/1e6:.1f} MB) -> {key}")
    t0 = time.time()
    with local.open("rb") as f:
        s3.put_object(Bucket=cfg["bucket"], Key=key, Body=f, ContentType="application/zip")
    print(f"  upload OK em {time.time() - t0:.0f}s")
    h = hashlib.sha256()
    with local.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    print(f"  sha256: {h.hexdigest()}")
    print(f"  URL: {public_base}/{key}")
print("PUBLICACAO OK")
