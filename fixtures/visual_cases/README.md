# Visual Test Cases Dataset (BE3-06)

## Overview

This directory contains 12 authorized visual test cases for multimodal model evaluation, divided into four categories:

### Food Photos (F01–F04)
Four educational food photos with known nutritional properties.

### Health Screenshots (H01–H04)
Four screenshots representing health tracking data:
- H01–H02: UI variant 1 (steps, sleep, heart rate)
- H03–H04: UI variant 2 (same metrics, different layout)

### Watch/Fitness Photos (W01–W02)
Two photographs of educational smartwatch/fitness tracker displays.

### Negative Cases (N01–N02)
Two test cases designed to be unreadable or unsupported:
- N01: Text that cannot be reliably read (blurred, rotated)
- N02: Image type that is not supported by the system

## File Structure

```
fixtures/visual_cases/
├── README.md
├── manifest.json
├── manifest-schema.json
├── food/
│   ├── F01.png
│   ├── F02.png
│   ├── F03.png
│   └── F04.png
├── health/
│   ├── H01.jpeg
│   ├── H02.jpeg
│   ├── H03.jpeg
│   └── H04.jpeg
├── watch/
│   ├── W01.jpeg
│   └── W02.jpeg
└── negative/
    ├── N01.jpeg
    └── N02.jpeg
```

## Manifest Structure

Each case in `manifest.json` contains:
- `id`: Unique identifier (F01, H01, etc.)
- `path`: Relative path to image file
- `source`: Source/license information
- `category`: food|health|watch|negative
- `expected_fields`: List of visible fields with units
- `datetime`: Expected date/time if visible
- `uncertainties`: Known limitations or ambiguities
- `expected_action`: draft (prompt for correction) | refuse (not supported)

## Versioning

Dataset changes must be recorded with:
- Version number
- Commit hash
- Date
- List of modified cases

All changes to images must be accompanied by corresponding manifest updates to maintain consistency.

## Usage in BE2

BE2 uses this dataset to:
1. Validate food photo recognition (F01–F04)
2. Test health screen capture handling (H01–H04)
3. Verify smartwatch support (W01–W02)
4. Ensure graceful failure on unsupported inputs (N01–N02)

The manifest must not be sent to the model as validation data.

## Visual cases validation
Setup
    python -m venv .venv
# Windows (PowerShell)
    . .venv/Scripts/Activate.ps1
# Linux/macOS
    # source .venv/bin/activate

    pip install -r fixtures/visual_cases/requirements.txt
Run manifest validation
    python fixtures/visual_cases/validate_manifest.py fixtures/visual_cases/manifest.json
Run tests
    pytest fixtures/visual_cases -q
