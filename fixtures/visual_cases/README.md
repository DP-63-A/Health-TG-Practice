# Visual cases dataset

This folder contains a synthetic dataset for BE3-06a: 12 visual samples with a manifest and JSON Schema validation.

The files are intentionally generated, without any personal data or real Telegram IDs.

Structure:
- `manifest.json` — dataset definition and expected recognition targets
- `manifest.schema.json` — JSON Schema for validation
- `validate_manifest.py` — lightweight validator
- 12 generated SVG samples in the same folder

The samples are categorised as:
- F01-F04: food photos
- H01-H04: health screens
- W01-W02: wearable/activity snapshots
- N01-N02: non-relevant/noisy input
