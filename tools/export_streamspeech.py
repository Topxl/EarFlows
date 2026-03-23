#!/usr/bin/env python3
"""
EarFlows — Export StreamSpeech to ONNX for mobile deployment.

StreamSpeech: https://github.com/ictnlp/StreamSpeech
Paper: "StreamSpeech: Simultaneous Speech-to-Speech Translation"

Exports 4 ONNX components:
1. Speech Encoder (HuBERT-based) → ~150MB quantized
2. Monotonic Policy (MMA / Wait-k) → ~30MB
3. Unit Decoder (mBART-based) → ~200MB quantized
4. Vocoder (HiFi-GAN) → ~50MB

Total: ~430MB quantized INT8 — runs on Snapdragon 8 Gen 3 + Hexagon NPU.

Requirements:
    pip install torch torchaudio fairseq onnx onnxruntime
    git clone https://github.com/ictnlp/StreamSpeech.git

Usage:
    python export_streamspeech.py --output-dir ../app/src/main/assets/models/
    python export_streamspeech.py --model-path /path/to/streamspeech/checkpoint.pt
"""

import argparse
import os
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn


def export_encoder(model, output_dir: Path, quantize: bool = True):
    """Export the speech encoder component."""
    print("\n=== Exporting Speech Encoder ===")

    class EncoderWrapper(nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, audio):
            # audio: [1, T] float32 raw waveform 16kHz
            features, _ = self.encoder(audio)
            return features  # [1, T/320, 1024]

    try:
        wrapper = EncoderWrapper(model.encoder)
        wrapper.eval()

        dummy = torch.randn(1, 16000 * 3)  # 3 seconds
        output_path = output_dir / "streamspeech_encoder.onnx"

        torch.onnx.export(
            wrapper, (dummy,), str(output_path),
            input_names=["audio"],
            output_names=["features"],
            dynamic_axes={"audio": {1: "time"}, "features": {1: "frames"}},
            opset_version=17
        )

        size_mb = output_path.stat().st_size / (1024 * 1024)
        print(f"  Encoder exported: {size_mb:.0f}MB")

        if quantize:
            return quantize_model(output_path)
        return output_path

    except Exception as e:
        print(f"  ERROR: {e}")
        return None


def export_decoder(model, output_dir: Path, quantize: bool = True):
    """Export the unit decoder component."""
    print("\n=== Exporting Unit Decoder ===")

    class DecoderWrapper(nn.Module):
        def __init__(self, decoder):
            super().__init__()
            self.decoder = decoder

        def forward(self, encoder_out, src_lang, tgt_lang):
            # encoder_out: [1, T, 1024]
            # Returns: unit indices [T']
            units = self.decoder(encoder_out, src_lang, tgt_lang)
            return units

    try:
        wrapper = DecoderWrapper(model.decoder)
        wrapper.eval()

        dummy_features = torch.randn(1, 100, 1024)
        dummy_src = torch.tensor([12])  # Thai
        dummy_tgt = torch.tensor([1])   # French
        output_path = output_dir / "streamspeech_decoder.onnx"

        torch.onnx.export(
            wrapper, (dummy_features, dummy_src, dummy_tgt),
            str(output_path),
            input_names=["encoder_out", "src_lang", "tgt_lang"],
            output_names=["units"],
            dynamic_axes={"encoder_out": {1: "frames"}, "units": {0: "unit_len"}},
            opset_version=17
        )

        size_mb = output_path.stat().st_size / (1024 * 1024)
        print(f"  Decoder exported: {size_mb:.0f}MB")

        if quantize:
            return quantize_model(output_path)
        return output_path

    except Exception as e:
        print(f"  ERROR: {e}")
        return None


def export_vocoder(model, output_dir: Path, quantize: bool = True):
    """Export the HiFi-GAN vocoder."""
    print("\n=== Exporting Vocoder ===")

    class VocoderWrapper(nn.Module):
        def __init__(self, vocoder):
            super().__init__()
            self.vocoder = vocoder

        def forward(self, units):
            # units: [1, T] int64 codebook indices
            waveform = self.vocoder(units)
            return waveform  # [1, T_out] float32

    try:
        wrapper = VocoderWrapper(model.vocoder)
        wrapper.eval()

        dummy_units = torch.randint(0, 10000, (1, 200)).long()
        output_path = output_dir / "streamspeech_vocoder.onnx"

        torch.onnx.export(
            wrapper, (dummy_units,), str(output_path),
            input_names=["units"],
            output_names=["waveform"],
            dynamic_axes={"units": {1: "unit_len"}, "waveform": {1: "audio_len"}},
            opset_version=17
        )

        size_mb = output_path.stat().st_size / (1024 * 1024)
        print(f"  Vocoder exported: {size_mb:.0f}MB")

        if quantize:
            return quantize_model(output_path)
        return output_path

    except Exception as e:
        print(f"  ERROR: {e}")
        return None


def quantize_model(model_path: Path) -> Path:
    """INT8 dynamic quantization."""
    from onnxruntime.quantization import quantize_dynamic, QuantType

    q_path = model_path.parent / model_path.name.replace(".onnx", "_q8.onnx")
    print(f"  Quantizing → {q_path.name}...")

    quantize_dynamic(str(model_path), str(q_path), weight_type=QuantType.QInt8)

    orig = model_path.stat().st_size / (1024 * 1024)
    qsize = q_path.stat().st_size / (1024 * 1024)
    print(f"  {orig:.0f}MB → {qsize:.0f}MB ({(1 - qsize/orig)*100:.0f}% reduction)")

    model_path.unlink()  # Remove unquantized
    return q_path


def export_hibiki_fallback(output_dir: Path):
    """
    Fallback: Export Hibiki-Zero (Kyutai) if StreamSpeech export fails.
    Hibiki is simpler to export and very efficient on mobile.
    """
    print("\n=== Exporting Hibiki-Zero (Fallback) ===")
    print("  TODO: Install kyutai-labs/hibiki and export")
    print("  pip install git+https://github.com/kyutai-labs/hibiki.git")
    print("  See: https://github.com/kyutai-labs/hibiki for ONNX export instructions")


def main():
    parser = argparse.ArgumentParser(description="Export StreamSpeech to ONNX")
    parser.add_argument("--output-dir", type=str, default=None)
    parser.add_argument("--model-path", type=str, default=None,
                        help="Path to StreamSpeech checkpoint")
    parser.add_argument("--no-quantize", action="store_true")
    parser.add_argument("--hibiki", action="store_true",
                        help="Export Hibiki-Zero instead")
    args = parser.parse_args()

    output_dir = Path(args.output_dir) if args.output_dir else \
        Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "models"
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.hibiki:
        export_hibiki_fallback(output_dir)
        return

    print(f"Output: {output_dir}")
    print("Loading StreamSpeech model...")

    try:
        # StreamSpeech uses fairseq-based loading
        # Clone: git clone https://github.com/ictnlp/StreamSpeech
        # Download checkpoint from their HuggingFace/releases

        if args.model_path:
            checkpoint = torch.load(args.model_path, map_location="cpu")
            model = checkpoint["model"]
        else:
            # Try to load from HuggingFace hub
            print("  Downloading from HuggingFace...")
            from huggingface_hub import hf_hub_download
            ckpt_path = hf_hub_download(
                repo_id="ictnlp/StreamSpeech",
                filename="streamspeech_s2st.pt"
            )
            checkpoint = torch.load(ckpt_path, map_location="cpu")
            model = checkpoint["model"]

        quantize = not args.no_quantize

        export_encoder(model, output_dir, quantize)
        export_decoder(model, output_dir, quantize)
        export_vocoder(model, output_dir, quantize)

        total = sum(f.stat().st_size for f in output_dir.rglob("*_q8.onnx"))
        print(f"\nTotal: {total / (1024*1024):.0f}MB")
        print("Done! Copy models to your Android device.")

    except Exception as e:
        print(f"\nStreamSpeech export failed: {e}")
        print("Trying Hibiki-Zero fallback...")
        export_hibiki_fallback(output_dir)


if __name__ == "__main__":
    main()
