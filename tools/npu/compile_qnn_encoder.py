#!/usr/bin/env python3
import argparse
import os
import subprocess
from pathlib import Path


def find_tool(root: Path, name: str) -> Path:
    matches = list(root.rglob(name))
    if not matches:
        raise SystemExit(f"Ferramenta {name} não encontrada em QNN_SDK_ROOT={root}")
    return matches[0]


def run(command):
    print("+", " ".join(map(str, command)))
    subprocess.run(list(map(str, command)), check=True)


def main():
    parser = argparse.ArgumentParser(description="Converte ONNX para uma model library QNN experimental.")
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target", default="aarch64-android")
    args = parser.parse_args()

    sdk = os.environ.get("QNN_SDK_ROOT") or os.environ.get("QAIRT_SDK_ROOT")
    if not sdk:
        raise SystemExit("Defina QNN_SDK_ROOT/QAIRT_SDK_ROOT para uma instalação oficial autorizada.")
    root = Path(sdk)
    converter = find_tool(root, "qnn-onnx-converter")
    generator = find_tool(root, "qnn-model-lib-generator")
    args.output.mkdir(parents=True, exist_ok=True)
    cpp = args.output / "encoder.cpp"
    binary = args.output / "encoder.bin"
    run([converter, "--input_network", args.onnx, "--output_path", cpp])
    if not cpp.exists():
        raise SystemExit("O conversor não produziu encoder.cpp. Configure calibração/quantização conforme a versão QAIRT.")
    run([generator, "-c", cpp, "-b", binary, "-o", args.output / "lib", "-t", args.target])
    print("Model library gerada. Context binary HTP ainda deve ser criado para o SoC alvo com as ferramentas da mesma versão QAIRT.")


if __name__ == "__main__":
    main()
