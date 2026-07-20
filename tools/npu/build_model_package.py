#!/usr/bin/env python3
import argparse
import hashlib
import json
import zipfile
from pathlib import Path


MODELS = {
    "tiny": ("whisper-tiny-hybrid-qnn-vulkan", "openai/whisper-tiny", 80, 384),
    "base": ("whisper-base-hybrid-qnn-vulkan", "openai/whisper-base", 80, 512),
    "turbo": ("whisper-large-v3-turbo-hybrid-qnn-vulkan", "openai/whisper-large-v3-turbo", 128, 1280),
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Monta pacote importável pela área Testes NPU.")
    parser.add_argument("--model", choices=MODELS, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--qnn-version", required=True)
    parser.add_argument("--soc", action="append", required=True, help="SoC compatível; repita para mais de um")
    parser.add_argument("--htp", action="append", required=True, help="Arquitetura HTP compatível; repita para mais de uma")
    args = parser.parse_args()

    files = [item for item in args.artifacts.rglob("*") if item.is_file()]
    if not files:
        raise SystemExit("Nenhum artefato encontrado.")
    model_id, checkpoint, mel_bins, output_size = MODELS[args.model]
    manifest = {
        "schemaVersion": 1,
        "modelId": model_id,
        "checkpoint": checkpoint,
        "variant": "multilingual",
        "encoderRuntime": "qnn-htp",
        "decoderRuntime": "whisper-vulkan",
        "qnnSdkVersion": args.qnn_version,
        "supportedSocIds": args.soc,
        "supportedHtpArchitectures": args.htp,
        "melBins": mel_bins,
        "audioContextFrames": 3000,
        "encoderOutputFrames": 1500,
        "encoderOutputSize": output_size,
        "files": [
            {"name": str(path.relative_to(args.artifacts)).replace("\\", "/"), "sha256": sha256(path), "size": path.stat().st_size}
            for path in files
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("package.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for path in files:
            archive.write(path, str(path.relative_to(args.artifacts)).replace("\\", "/"))
    print(f"Pacote criado: {args.output}")


if __name__ == "__main__":
    main()
