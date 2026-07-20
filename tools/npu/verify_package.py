#!/usr/bin/env python3
import argparse
import hashlib
import json
import zipfile
from pathlib import PurePosixPath


def main():
    parser = argparse.ArgumentParser(description="Valida estrutura e SHA-256 de pacote NPU.")
    parser.add_argument("package")
    args = parser.parse_args()
    with zipfile.ZipFile(args.package) as archive:
        manifest = json.loads(archive.read("package.json"))
        if manifest.get("schemaVersion") != 1:
            raise SystemExit("Schema incompatível.")
        for item in manifest.get("files", []):
            name = item["name"]
            if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
                raise SystemExit(f"Caminho inseguro: {name}")
            actual = hashlib.sha256(archive.read(name)).hexdigest()
            if actual != item["sha256"].lower():
                raise SystemExit(f"Hash incorreto: {name}")
        print(f"Pacote válido: {manifest['modelId']} ({len(manifest.get('files', []))} arquivos)")


if __name__ == "__main__":
    main()
