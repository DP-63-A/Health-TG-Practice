#!/usr/bin/env python3
"""
Validator for visual_cases manifest.json

Checks:
- JSON schema validity
- File existence and consistency
- Case uniqueness and completeness
- Date format and range
- Image format (JPEG/PNG only)
- Image file integrity and decodability
- Image size (max 5 MiB)
- Image resolution (max 12 megapixels)
"""

import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Set, Tuple, Optional

try:
    from jsonschema import validate, ValidationError, FormatChecker
except ImportError:
    print("ERROR: jsonschema not installed. Run: pip install jsonschema")
    sys.exit(1)

try:
    from PIL import Image
except ImportError:
    print("ERROR: Pillow not installed. Run: pip install Pillow")
    sys.exit(1)


def load_manifest(manifest_path: Path) -> Dict:
    """Load and parse manifest.json"""
    try:
        with open(manifest_path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"ERROR: Manifest file not found: {manifest_path}")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"ERROR: Invalid JSON in manifest: {e}")
        sys.exit(1)


def load_schema(schema_path: Path) -> Dict:
    """Load JSON schema for validation"""
    try:
        with open(schema_path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"ERROR: Schema file not found: {schema_path}")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"ERROR: Invalid JSON in schema: {e}")
        sys.exit(1)


def validate_schema(manifest: Dict, schema: Dict) -> None:
    """Validate manifest against JSON schema"""
    try:
        validate(instance=manifest, schema=schema, format_checker=FormatChecker())
    except ValidationError as e:
        print(f"ERROR: Schema validation failed: {e.message}")
        sys.exit(1)


def validate_file_existence(base_dir: Path, manifest: Dict) -> None:
    """Verify all referenced files exist"""
    errors = []
    for case in manifest.get('cases', []):
        file_path = base_dir / case.get('path', '')
        if not file_path.exists():
            errors.append(f"  - File missing: {case['id']} -> {case['path']}")
        elif not file_path.is_file():
            errors.append(f"  - Not a file: {case['id']} -> {case['path']}")
    
    if errors:
        print("ERROR: Missing files:")
        for err in errors:
            print(err)
        sys.exit(1)


def validate_image_format(file_path: Path, case_id: str) -> Tuple[bool, Optional[str]]:
    """
    Validate image format (JPEG or PNG only) by reading image container metadata.
    Returns: (is_valid, error_message)
    """
    suffix = file_path.suffix.lower()
    if suffix not in ['.jpg', '.jpeg', '.png']:
        return False, f"Unsupported extension: {suffix} (expected .jpg, .jpeg, or .png)"

    try:
        with Image.open(file_path) as img:
            fmt = (img.format or "").upper()
            if fmt not in ['JPEG', 'PNG']:
                return False, f"Invalid image format: {fmt} (file content is not JPEG/PNG, extension is {suffix})"
    except Exception as e:
        return False, f"Cannot detect image format: {str(e)}"

    return True, None


def validate_image_integrity(file_path: Path, case_id: str) -> Tuple[bool, Optional[str]]:
    """
    Validate image file integrity by enforcing full pixel load and stream verification.
    Returns: (is_valid, error_message)
    """
    try:
        # Step 1: Verify structural integrity
        with Image.open(file_path) as img:
            img.verify()
        
        # Step 2: Force full pixel decoding (detect truncated/corrupted stream)
        with Image.open(file_path) as img:
            img.load()
            
        return True, None
    except Exception as e:
        return False, f"Corrupted or truncated image data: {str(e)}"


def validate_image_size(file_path: Path, case_id: str, max_size_mb: int = 5) -> Tuple[bool, Optional[str]]:
    """
    Validate image file size (max 5 MiB by default).
    Returns: (is_valid, error_message)
    """
    max_size_bytes = max_size_mb * 1024 * 1024
    file_size_bytes = file_path.stat().st_size
    
    if file_size_bytes > max_size_bytes:
        file_size_mb = file_size_bytes / (1024 * 1024)
        return False, f"File size {file_size_mb:.2f} MiB exceeds limit of {max_size_mb} MiB"
    return True, None


def validate_image_resolution(file_path: Path, case_id: str, max_megapixels: float = 12.0) -> Tuple[bool, Optional[str]]:
    """
    Validate image resolution (max 12 megapixels = 12,000,000 pixels).
    Returns: (is_valid, error_message)
    """
    max_pixels = int(max_megapixels * 1_000_000)
    try:
        with Image.open(file_path) as img:
            width, height = img.size
            total_pixels = width * height
            
            if total_pixels > max_pixels:
                mp_actual = total_pixels / 1_000_000
                return False, f"Resolution {width}×{height} ({mp_actual:.2f} MP / {total_pixels:,} px) exceeds limit of {max_megapixels} MP ({max_pixels:,} px)"
        return True, None
    except Exception as e:
        return False, f"Cannot determine resolution: {str(e)}"


def validate_images(base_dir: Path, manifest: Dict) -> None:
    """
    Validate all images against format, integrity, size, and resolution constraints.
    """
    errors = []
    
    for case in manifest.get('cases', []):
        case_id = case.get('id')
        file_path = base_dir / case.get('path', '')
        
        if not file_path.exists():
            continue  # Already caught by validate_file_existence
        
        # Check format
        is_valid, error_msg = validate_image_format(file_path, case_id)
        if not is_valid:
            errors.append(f"  - {case_id} ({file_path}): {error_msg}")
            continue
        
        # Check integrity (decodability)
        is_valid, error_msg = validate_image_integrity(file_path, case_id)
        if not is_valid:
            errors.append(f"  - {case_id} ({file_path}): {error_msg}")
            continue
        
        # Check file size
        is_valid, error_msg = validate_image_size(file_path, case_id)
        if not is_valid:
            errors.append(f"  - {case_id} ({file_path}): {error_msg}")
            continue
        
        # Check resolution
        is_valid, error_msg = validate_image_resolution(file_path, case_id)
        if not is_valid:
            errors.append(f"  - {case_id} ({file_path}): {error_msg}")
    
    if errors:
        print("ERROR: Image validation failed:")
        for err in errors:
            print(err)
        sys.exit(1)


def validate_case_uniqueness(manifest: Dict) -> None:
    """Check for duplicate IDs and paths"""
    ids: Set[str] = set()
    paths: Set[str] = set()
    id_errors = []
    path_errors = []
    
    for case in manifest.get('cases', []):
        case_id = case.get('id')
        case_path = case.get('path')
        
        if case_id in ids:
            id_errors.append(f"  - Duplicate ID: {case_id}")
        else:
            ids.add(case_id)
        
        if case_path in paths:
            path_errors.append(f"  - Duplicate path: {case_path}")
        else:
            paths.add(case_path)
    
    if id_errors:
        print("ERROR: Duplicate IDs:")
        for err in id_errors:
            print(err)
        sys.exit(1)
    
    if path_errors:
        print("ERROR: Duplicate paths:")
        for err in path_errors:
            print(err)
        sys.exit(1)


def validate_case_count_and_coverage(manifest: Dict) -> None:
    """Verify exactly 12 cases with all required categories"""
    cases = manifest.get('cases', [])
    
    # Check count
    if len(cases) != 12:
        print(f"ERROR: Expected 12 cases, got {len(cases)}")
        sys.exit(1)
    
    # Check category coverage
    required_ids = {
        'food': {'F01', 'F02', 'F03', 'F04'},
        'health': {'H01', 'H02', 'H03', 'H04'},
        'watch': {'W01', 'W02'},
        'negative': {'N01', 'N02'}
    }
    
    found_ids: Dict[str, Set[str]] = {
        'food': set(),
        'health': set(),
        'watch': set(),
        'negative': set()
    }
    
    for case in cases:
        category = case.get('category')
        case_id = case.get('id')
        if category in found_ids:
            found_ids[category].add(case_id)
    
    errors = []
    for category, expected_ids in required_ids.items():
        missing = expected_ids - found_ids[category]
        if missing:
            errors.append(f"  - {category}: missing {sorted(missing)}")
    
    if errors:
        print("ERROR: Category coverage incomplete:")
        for err in errors:
            print(err)
        sys.exit(1)


def validate_dates(manifest: Dict) -> Tuple[datetime, datetime]:
    """Validate ISO8601 date formats and extract date range"""
    dates = []
    errors = []
    
    for case in manifest.get('cases', []):
        case_id = case.get('id')
        datetime_obj = case.get('datetime', {})
        date_str = datetime_obj.get('date')
        time_str = datetime_obj.get('time')
        
        if date_str:
            try:
                parsed_date = datetime.fromisoformat(date_str)
                dates.append(parsed_date)
            except ValueError:
                errors.append(f"  - Invalid date format in {case_id}: {date_str}")
        
        if time_str:
            try:
                # Validate time format HH:MM
                parts = time_str.split(':')
                if len(parts) != 2:
                    raise ValueError(f"Expected HH:MM format, got {time_str}")
                hour, minute = int(parts[0]), int(parts[1])
                if not (0 <= hour <= 23):
                    raise ValueError(f"Hour out of range: {hour}")
                if not (0 <= minute <= 59):
                    raise ValueError(f"Minute out of range: {minute}")
            except (ValueError, TypeError) as e:
                errors.append(f"  - Invalid time format in {case_id}: {time_str} ({e})")
    
    if errors:
        print("ERROR: Date/time validation failed:")
        for err in errors:
            print(err)
        sys.exit(1)
    
    if not dates:
        print("WARNING: No dates found in manifest")
        return None, None
    
    min_date = min(dates)
    max_date = max(dates)
    return min_date, max_date


def validate_date_coverage(min_date: datetime, max_date: datetime) -> None:
    """Verify reasonable date range (should span multiple weeks)"""
    if min_date is None or max_date is None:
        return
    
    date_range = (max_date - min_date).days
    if date_range < 3:
        print(f"WARNING: Date range is only {date_range} days. Consider spanning multiple weeks.")


def validate_expected_actions(manifest: Dict) -> None:
    """Verify expected_action values are valid"""
    valid_actions = {'draft', 'ask', 'refuse'}
    errors = []
    
    for case in manifest.get('cases', []):
        case_id = case.get('id')
        action = case.get('expected_action')
        if action not in valid_actions:
            errors.append(f"  - {case_id}: invalid action '{action}' (expected one of {valid_actions})")
    
    if errors:
        print("ERROR: Invalid expected_action values:")
        for err in errors:
            print(err)
        sys.exit(1)


def main():
    """Run all validations"""
    base_dir = Path(__file__).parent
    manifest_path = base_dir / 'manifest.json'
    schema_path = base_dir / 'manifest-schema.json'
    
    print(f"Validating manifest: {manifest_path}")
    print()
    
    # Load files
    manifest = load_manifest(manifest_path)
    schema = load_schema(schema_path)
    
    # Run validations
    print("✓ Checking JSON schema...")
    validate_schema(manifest, schema)
    
    print("✓ Checking case uniqueness...")
    validate_case_uniqueness(manifest)
    
    print("✓ Checking case count and coverage...")
    validate_case_count_and_coverage(manifest)
    
    print("✓ Checking file existence...")
    validate_file_existence(base_dir, manifest)
    
    print("✓ Validating image format and integrity...")
    validate_images(base_dir, manifest)
    
    print("✓ Validating dates and times...")
    min_date, max_date = validate_dates(manifest)
    
    print("✓ Checking date coverage...")
    if min_date and max_date:
        validate_date_coverage(min_date, max_date)
        print(f"  Date range: {min_date.date()} to {max_date.date()}")
    
    print("✓ Validating expected_action values...")
    validate_expected_actions(manifest)
    
    print()
    print("✅ All validations passed!")
    print(f"   - 12 cases present")
    print(f"   - All categories covered (F, H, W, N)")
    print(f"   - All referenced files exist")
    print(f"   - All images are valid (format, integrity, size, resolution)")
    print(f"   - Date/time format valid")
    print(f"   - No duplicates found")


if __name__ == '__main__':
    main()
