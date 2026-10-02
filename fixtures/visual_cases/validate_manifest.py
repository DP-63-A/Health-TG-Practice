#!/usr/bin/env python3
import json
from pathlib import Path

try:
    import jsonschema
except ImportError as exc:  # pragma: no cover
    raise SystemExit(
        "jsonschema package is required. Install it with: pip install jsonschema"
    ) from exc

ROOT = Path(__file__).resolve().parent
manifest = ROOT / "manifest.json"
schema = ROOT / "manifest.schema.json"

with manifest.open("r", encoding="utf-8") as f:
    data = json.load(f)
with schema.open("r", encoding="utf-8") as f:
    schema_doc = json.load(f)

jsonschema.validate(instance=data, schema=schema_doc)
items = data["items"]
print(f"Manifest valid. Items: {len(items)}/12")
print("All categories present:", sorted({item["category"] for item in items}))
