#!/usr/bin/env python3
import argparse
from pathlib import Path

import torch
from transformers import WhisperModel


MODELS = {
    "tiny": ("openai/whisper-tiny", 80),
    "base": ("openai/whisper-base", 80),
    "turbo": ("openai/whisper-large-v3-turbo", 128),
}


class EncoderOnly(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.encoder = model.encoder

    def forward(self, input_features):
        return self.encoder(input_features=input_features, return_dict=False)[0]


def main():
    parser = argparse.ArgumentParser(description="Exporta somente o encoder Whisper para ONNX.")
    parser.add_argument("--model", choices=MODELS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()

    checkpoint, mel_bins = MODELS[args.model]
    model = WhisperModel.from_pretrained(checkpoint).eval()
    encoder = EncoderOnly(model)
    sample = torch.zeros((1, mel_bins, 3000), dtype=torch.float32)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        encoder,
        (sample,),
        str(args.output),
        input_names=["input_features"],
        output_names=["last_hidden_state"],
        dynamic_axes=None,
        opset_version=args.opset,
        do_constant_folding=True,
    )
    print(f"Exportado {checkpoint} para {args.output}")
    print("ATENÇÃO: compare numericamente este ONNX com o checkpoint antes de compilar para HTP.")


if __name__ == "__main__":
    main()
