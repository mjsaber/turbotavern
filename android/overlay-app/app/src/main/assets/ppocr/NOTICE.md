# Vendored PP-OCRv5 models (hero-tier OCR engine)

PaddleOCR PP-OCRv5 mobile models, used by `PaddleHeroOcr` via ONNX Runtime
(`com.microsoft.onnxruntime:onnxruntime-android`). License: **Apache-2.0** (PaddleOCR /
PaddlePaddle). Chosen by the Stage-0 offline bake-off — see
`../../../../recordings/ocr-corpus/PPOCRV5-OFFLINE.md` and
`docs/superpowers/plans/2026-06-07-ppocrv5-ocr-engine.md`.

Source: RapidAI/RapidOCR model zoo on ModelScope, tag **v3.8.0** (ONNX exports of the official
PaddleOCR PP-OCRv5 mobile models).

| file | role | bytes | sha256 |
|---|---|---|---|
| `ch_PP-OCRv5_det_mobile.onnx` | DBNet text detection | 4819576 | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` |
| `ch_PP-OCRv5_rec_mobile.onnx` | SVTRv2 recognition (unified SC+TC+EN+JP) | 16631306 | `5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5` |
| `ppocrv5_dict.txt` | rec character dictionary (18,383 entries) | 74012 | `d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b` |

Upstream model URLs (ModelScope `RapidAI/RapidOCR` `resolve/v3.8.0/onnx/PP-OCRv5/...`):
- det: `.../det/ch_PP-OCRv5_det_mobile.onnx`
- rec: `.../rec/ch_PP-OCRv5_rec_mobile.onnx`
- dict: bundled with the `rapidocr` PyPI package (`rapidocr/models/ppocrv5_dict.txt`)

Total ~21.5 MB. Over the <20 MB target; rec int8 quantization is a tracked later lever (the rec
model is the bulk at 16.6 MB).
