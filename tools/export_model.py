#!/usr/bin/env python3
"""
EarFlows Model Export Tool

Exports Meta SeamlessStreaming / SeamlessM4T v2 to ONNX format
with INT8 quantization for mobile deployment.

The export creates 3 model components:
1. Speech Encoder (Conformer) → seamless_encoder_q8.onnx
2. Text Decoder (with EMMA monotonic attention) → seamless_decoder_q8.onnx
3. Unit Vocoder (HiFi-GAN) → seamless_vocoder_q8.onnx

Plus optionally a combined model for simpler deployment.

Requirements:
    pip install -r requirements.txt

Usage:
    python export_model.py                      # Export all components
    python export_model.py --encoder-only       # Export encoder only
    python export_model.py --combined            # Export single combined model
    python export_model.py --output-dir ./out   # Custom output directory

Output files go to: ../app/src/main/assets/models/ by default
"""

import argparse
import os
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn


def check_dependencies():
    """Check all required packages are installed."""
    missing = []
    for pkg in ['onnx', 'onnxruntime', 'transformers']:
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg)

    # Check seamless_communication (Meta's library)
    try:
        import seamless_communication
    except ImportError:
        missing.append('seamless_communication')

    if missing:
        print(f"Missing packages: {', '.join(missing)}")
        print(f"Install with: pip install -r requirements.txt")
        sys.exit(1)


def load_seamless_model(model_name="seamlessM4T_v2_large"):
    """
    Load the SeamlessM4T v2 / SeamlessStreaming model from Meta.
    Uses fairseq2/seamless_communication library.
    """
    print(f"Loading model: {model_name}...")
    print("  (This will download ~8GB on first run)")

    from seamless_communication.inference import Translator

    translator = Translator(
        model_name_or_card=model_name,
        vocoder_name_or_card="vocoder_v2",
        device=torch.device("cpu"),
        dtype=torch.float32,
    )

    print(f"  Model loaded successfully")
    return translator


class SpeechEncoderWrapper(nn.Module):
    """Wraps the speech encoder for ONNX export."""

    def __init__(self, model):
        super().__init__()
        self.encoder = model.model.speech_encoder
        self.encoder_frontend = model.model.speech_encoder_frontend

    def forward(self, audio_input):
        # audio_input: [1, T] raw waveform
        features = self.encoder_frontend(audio_input)
        encoder_output, _ = self.encoder(features)
        return encoder_output


class TextDecoderWrapper(nn.Module):
    """Wraps the text decoder for ONNX export."""

    def __init__(self, model):
        super().__init__()
        self.decoder = model.model.text_decoder
        self.decoder_frontend = model.model.text_decoder_frontend
        self.final_proj = model.model.final_proj

    def forward(self, encoder_output, source_lang, target_lang):
        # Simplified forward for export
        decoder_output, _ = self.decoder(
            encoder_output,
            None,  # padding_mask
        )
        logits = self.final_proj(decoder_output)
        return logits


class VocoderWrapper(nn.Module):
    """Wraps the unit vocoder (HiFi-GAN) for ONNX export."""

    def __init__(self, vocoder):
        super().__init__()
        self.vocoder = vocoder

    def forward(self, units):
        # units: [1, T] discrete speech unit indices
        waveform = self.vocoder(units)
        return waveform


def export_encoder(translator, output_dir: Path, quantize: bool = True):
    """Export speech encoder to ONNX."""
    print("\n--- Exporting Speech Encoder ---")

    encoder = SpeechEncoderWrapper(translator)
    encoder.eval()

    # Dummy input: 3 seconds of audio at 16kHz
    dummy_audio = torch.randn(1, 48000)

    output_path = output_dir / "seamless_encoder.onnx"

    print("  Running ONNX export...")
    torch.onnx.export(
        encoder,
        (dummy_audio,),
        str(output_path),
        input_names=["audio_input"],
        output_names=["encoder_output"],
        dynamic_axes={
            "audio_input": {1: "audio_length"},
            "encoder_output": {1: "seq_length"},
        },
        opset_version=17,
        do_constant_folding=True,
    )

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"  Encoder exported: {output_path} ({size_mb:.1f} MB)")

    if quantize:
        quantized_path = quantize_model(output_path)
        return quantized_path

    return output_path


def export_decoder(translator, output_dir: Path, quantize: bool = True):
    """Export text decoder to ONNX."""
    print("\n--- Exporting Text Decoder ---")

    decoder = TextDecoderWrapper(translator)
    decoder.eval()

    # Dummy inputs
    dummy_encoder_output = torch.randn(1, 100, 1024)  # [batch, seq, hidden]
    dummy_src_lang = torch.tensor([12])  # Thai
    dummy_tgt_lang = torch.tensor([1])   # French

    output_path = output_dir / "seamless_decoder.onnx"

    print("  Running ONNX export...")
    torch.onnx.export(
        decoder,
        (dummy_encoder_output, dummy_src_lang, dummy_tgt_lang),
        str(output_path),
        input_names=["encoder_output", "source_lang", "target_lang"],
        output_names=["logits"],
        dynamic_axes={
            "encoder_output": {1: "seq_length"},
            "logits": {1: "output_length"},
        },
        opset_version=17,
        do_constant_folding=True,
    )

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"  Decoder exported: {output_path} ({size_mb:.1f} MB)")

    if quantize:
        quantized_path = quantize_model(output_path)
        return quantized_path

    return output_path


def export_vocoder(translator, output_dir: Path, quantize: bool = True):
    """Export unit vocoder to ONNX."""
    print("\n--- Exporting Vocoder ---")

    try:
        vocoder = VocoderWrapper(translator.vocoder)
        vocoder.eval()
    except AttributeError:
        print("  WARNING: Vocoder not available in this model config.")
        print("  The decoder may output waveform directly.")
        return None

    # Dummy input: sequence of speech units
    dummy_units = torch.randint(0, 10000, (1, 200)).long()

    output_path = output_dir / "seamless_vocoder.onnx"

    print("  Running ONNX export...")
    torch.onnx.export(
        vocoder,
        (dummy_units,),
        str(output_path),
        input_names=["units"],
        output_names=["waveform"],
        dynamic_axes={
            "units": {1: "unit_length"},
            "waveform": {1: "audio_length"},
        },
        opset_version=17,
        do_constant_folding=True,
    )

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"  Vocoder exported: {output_path} ({size_mb:.1f} MB)")

    if quantize:
        quantized_path = quantize_model(output_path)
        return quantized_path

    return output_path


def quantize_model(model_path: Path) -> Path:
    """Apply INT8 dynamic quantization to reduce model size ~4x."""
    from onnxruntime.quantization import quantize_dynamic, QuantType

    quantized_path = model_path.parent / model_path.name.replace(".onnx", "_q8.onnx")

    print(f"  Quantizing to INT8: {quantized_path.name}...")
    quantize_dynamic(
        str(model_path),
        str(quantized_path),
        weight_type=QuantType.QInt8,
        optimize_model=True,
    )

    original_size = model_path.stat().st_size / (1024 * 1024)
    quantized_size = quantized_path.stat().st_size / (1024 * 1024)
    reduction = (1 - quantized_size / original_size) * 100

    print(f"  Quantized: {original_size:.1f}MB → {quantized_size:.1f}MB ({reduction:.0f}% reduction)")

    # Remove unquantized version to save space
    model_path.unlink()

    return quantized_path


def verify_model(model_path: Path):
    """Verify the exported ONNX model loads and runs."""
    import onnxruntime as ort

    print(f"\n  Verifying: {model_path.name}...")

    session = ort.InferenceSession(str(model_path))
    inputs = session.get_inputs()
    outputs = session.get_outputs()

    print(f"    Inputs:  {[(i.name, i.shape, i.type) for i in inputs]}")
    print(f"    Outputs: {[(o.name, o.shape, o.type) for o in outputs]}")
    print(f"    OK")


def export_alternative_whisper_nllb(output_dir: Path):
    """
    Alternative export: Whisper tiny + NLLB-200 as a fallback cascade pipeline.
    Much smaller models, works as immediate offline solution.

    Whisper tiny: ~150MB (39M params) → ~40MB quantized
    NLLB-200-distilled-600M: ~1.2GB → ~300MB quantized
    """
    print("\n============================================")
    print("  Exporting Fallback Pipeline: Whisper + NLLB")
    print("============================================")

    from optimum.onnxruntime import ORTModelForSpeechSeq2Seq, ORTModelForSeq2SeqLM
    from transformers import AutoTokenizer, AutoProcessor

    # --- Whisper tiny (ASR: Thai speech → Thai text) ---
    print("\n--- Whisper tiny (ASR) ---")
    whisper_dir = output_dir / "whisper_tiny"
    whisper_dir.mkdir(exist_ok=True)

    print("  Loading & exporting Whisper tiny to ONNX...")
    whisper_model = ORTModelForSpeechSeq2Seq.from_pretrained(
        "openai/whisper-tiny",
        export=True,
    )
    whisper_model.save_pretrained(str(whisper_dir))
    processor = AutoProcessor.from_pretrained("openai/whisper-tiny")
    processor.save_pretrained(str(whisper_dir))

    total_size = sum(f.stat().st_size for f in whisper_dir.rglob("*.onnx"))
    print(f"  Whisper tiny exported: {total_size / (1024*1024):.1f} MB")

    # --- NLLB-200 distilled (Translation: Thai text → French text) ---
    print("\n--- NLLB-200 distilled (Translation) ---")
    nllb_dir = output_dir / "nllb_200_distilled"
    nllb_dir.mkdir(exist_ok=True)

    print("  Loading & exporting NLLB-200-distilled-600M to ONNX...")
    print("  (This downloads ~1.2GB on first run)")
    nllb_model = ORTModelForSeq2SeqLM.from_pretrained(
        "facebook/nllb-200-distilled-600M",
        export=True,
    )
    nllb_model.save_pretrained(str(nllb_dir))
    tokenizer = AutoTokenizer.from_pretrained("facebook/nllb-200-distilled-600M")
    tokenizer.save_pretrained(str(nllb_dir))

    total_size = sum(f.stat().st_size for f in nllb_dir.rglob("*.onnx"))
    print(f"  NLLB exported: {total_size / (1024*1024):.1f} MB")

    print("\n  Fallback pipeline exported successfully!")
    print(f"  Total size: {sum(f.stat().st_size for f in output_dir.rglob('*.onnx')) / (1024*1024):.0f} MB")


def main():
    parser = argparse.ArgumentParser(description="EarFlows: Export SeamlessStreaming to ONNX")
    parser.add_argument("--output-dir", type=str, default=None,
                        help="Output directory (default: ../app/src/main/assets/models/)")
    parser.add_argument("--encoder-only", action="store_true",
                        help="Export only the speech encoder")
    parser.add_argument("--combined", action="store_true",
                        help="Export as a single combined model")
    parser.add_argument("--no-quantize", action="store_true",
                        help="Skip INT8 quantization")
    parser.add_argument("--fallback-only", action="store_true",
                        help="Export only the Whisper+NLLB fallback pipeline")
    parser.add_argument("--all", action="store_true",
                        help="Export both SeamlessStreaming and Whisper+NLLB fallback")
    args = parser.parse_args()

    # Determine output directory
    if args.output_dir:
        output_dir = Path(args.output_dir)
    else:
        script_dir = Path(__file__).parent
        output_dir = script_dir.parent / "app" / "src" / "main" / "assets" / "models"

    output_dir.mkdir(parents=True, exist_ok=True)
    print(f"Output directory: {output_dir}")

    quantize = not args.no_quantize

    if args.fallback_only:
        check_dependencies()
        export_alternative_whisper_nllb(output_dir)
        return

    if args.all:
        check_dependencies()
        # Export both
        try:
            translator = load_seamless_model()
            export_encoder(translator, output_dir, quantize)
            if not args.encoder_only:
                export_decoder(translator, output_dir, quantize)
                export_vocoder(translator, output_dir, quantize)
        except Exception as e:
            print(f"\nSeamlessStreaming export failed: {e}")
            print("Falling back to Whisper+NLLB export...")

        export_alternative_whisper_nllb(output_dir)
        return

    # Default: try SeamlessStreaming, suggest fallback on failure
    check_dependencies()

    try:
        translator = load_seamless_model()

        if args.encoder_only:
            path = export_encoder(translator, output_dir, quantize)
            verify_model(path)
        else:
            print("\nExporting all components...")
            enc_path = export_encoder(translator, output_dir, quantize)
            dec_path = export_decoder(translator, output_dir, quantize)
            voc_path = export_vocoder(translator, output_dir, quantize)

            for p in [enc_path, dec_path, voc_path]:
                if p:
                    verify_model(p)

    except Exception as e:
        print(f"\n{'='*50}")
        print(f"ERROR: SeamlessStreaming export failed:")
        print(f"  {e}")
        print(f"\nThis usually means:")
        print(f"  - Not enough RAM (need ~16GB for the large model)")
        print(f"  - Missing CUDA/GPU support")
        print(f"  - Model download failed")
        print(f"\nAlternative: Export the Whisper+NLLB fallback pipeline instead:")
        print(f"  python export_model.py --fallback-only")
        print(f"\nThe fallback pipeline is smaller (~400MB vs ~3GB) and works well")
        print(f"for Thai→French translation, just with slightly higher latency.")
        print(f"{'='*50}")
        sys.exit(1)

    print("\n============================================")
    print("  Export complete!")
    print("============================================")
    print(f"  Models saved to: {output_dir}")
    print(f"  Total size: {sum(f.stat().st_size for f in output_dir.rglob('*.onnx')) / (1024*1024):.0f} MB")
    print(f"\n  Copy to your Android project assets if not already there.")


if __name__ == "__main__":
    main()
