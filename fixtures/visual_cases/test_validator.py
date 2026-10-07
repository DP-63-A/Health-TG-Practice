import copy
import io
import json
import pytest
from pathlib import Path
from jsonschema import Draft7Validator, ValidationError
from PIL import Image
from validate_manifest import validate_image_format, validate_image_integrity, validate_image_resolution, validate_manifest

HERE = Path(__file__).resolve().parent
REAL_MANIFEST = HERE / "manifest.json"
REAL_SCHEMA = HERE / "manifest-schema.json"


def test_gif_disguised_as_png_fails(tmp_path: Path):
    """Тест 1: GIF под видом .png должен отклоняться"""
    fake_png = tmp_path / "fake.png"

    # Создаем реальный GIF и сохраняем с расширением .png
    img = Image.new('RGB', (100, 100), color='red')
    img.save(fake_png, format='GIF')

    is_valid, err = validate_image_format(fake_png, "TEST_GIF")
    assert not is_valid
    assert "Invalid image format: GIF" in err


def test_truncated_jpeg_fails(tmp_path: Path):
    """Тест 2: Обрезанный JPEG должен ломаться на load()"""
    truncated_jpg = tmp_path / "truncated.jpg"

    # Создаем валидный JPEG и срезаем последние байты (данные изображения)
    buffer = io.BytesIO()
    img = Image.new('RGB', (200, 200), color='blue')
    img.save(buffer, format='JPEG')
    data = buffer.getvalue()

    # Обрезаем 30% файла с конца
    truncated_jpg.write_bytes(data[:int(len(data) * 0.7)])

    is_valid, err = validate_image_integrity(truncated_jpg, "TEST_TRUNCATED")
    assert not is_valid
    assert "Corrupted or truncated image data" in err


def test_image_exceeding_12mp_fails(tmp_path: Path):
    """Тест 3: Кадр 4000x3001 (12 004 000 пикселей) превышает порог 12 MP"""
    large_jpg = tmp_path / "oversized.jpg"

    # Создаем пустой заголовок изображения 4000x3001
    img = Image.new('RGB', (4000, 3001))
    img.save(large_jpg, format='JPEG')

    is_valid, err = validate_image_resolution(large_jpg, "TEST_OVERSIZED", max_megapixels=12.0)
    assert not is_valid
    assert "exceeds limit of 12" in err


# ---------------------------------------------------------------------------
# Тесты схемы манифеста.
#
# Берём реальный манифест как базу (в нём ровно 12 допустимых кейсов), портим
# ровно одно место и пишем копию во временную папку. validate_manifest()
# загружает manifest-schema.json относительно validate_manifest.py, поэтому
# схема во временной папке не нужна. Ошибка схемы возникает до проверки файлов
# изображений, так что картинки во временной папке тоже не нужны.
# ---------------------------------------------------------------------------

def _load_real_manifest() -> dict:
    return copy.deepcopy(json.loads(REAL_MANIFEST.read_text(encoding="utf-8")))


def _case_index(manifest: dict, case_id: str) -> int:
    return next(i for i, c in enumerate(manifest["cases"]) if c["id"] == case_id)


def _schema_error(tmp_path: Path, manifest: dict) -> ValidationError:
    broken = tmp_path / "broken.json"
    broken.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ValidationError) as exc:
        validate_manifest(str(broken))

    # Обёртка сохраняет исходную ошибку jsonschema в __cause__
    cause = exc.value.__cause__
    assert isinstance(cause, ValidationError)
    return cause


def test_real_manifest_satisfies_schema():
    """Контроль: исправленная схема принимает реальный манифест"""
    schema = json.loads(REAL_SCHEMA.read_text(encoding="utf-8"))
    Draft7Validator.check_schema(schema)
    errors = list(Draft7Validator(schema).iter_errors(_load_real_manifest()))
    assert errors == []


def test_conditional_rules_are_defined_at_case_level():
    """allOf должен лежать рядом с properties/required кейса, а не внутри properties"""
    schema = json.loads(REAL_SCHEMA.read_text(encoding="utf-8"))
    case_schema = schema["properties"]["cases"]["items"]
    assert isinstance(case_schema.get("allOf"), list)
    assert len(case_schema["allOf"]) == 4
    assert "allOf" not in case_schema["properties"]


def test_manifest_validation_fails_on_missing_required_field(tmp_path: Path):
    """Тест 4: пропущенные value/unit/confidence в expected_fields health-кейса"""
    manifest = _load_real_manifest()
    idx = _case_index(manifest, "H01")
    del manifest["cases"][idx]["expected_fields"][0]["value"]

    error = _schema_error(tmp_path, manifest)

    assert error.validator == "required"
    assert "value" in error.message
    assert list(error.absolute_path) == ["cases", idx, "expected_fields", 0]


def test_food_requires_non_empty_expected_fields(tmp_path: Path):
    """minProperties для food"""
    manifest = _load_real_manifest()
    idx = _case_index(manifest, "F01")
    manifest["cases"][idx]["expected_fields"] = {}

    error = _schema_error(tmp_path, manifest)

    assert error.validator == "minProperties"
    assert list(error.absolute_path) == ["cases", idx, "expected_fields"]


def test_food_expected_field_requires_value_unit_confidence(tmp_path: Path):
    """Каждое поле food должно содержать value, unit, confidence"""
    manifest = _load_real_manifest()
    idx = _case_index(manifest, "F02")
    del manifest["cases"][idx]["expected_fields"]["calories"]["confidence"]

    error = _schema_error(tmp_path, manifest)

    assert error.validator == "required"
    assert "confidence" in error.message


@pytest.mark.parametrize("case_id", ["H01", "W01"])
def test_health_and_watch_require_non_empty_expected_fields(tmp_path: Path, case_id: str):
    """minItems для health/watch"""
    manifest = _load_real_manifest()
    idx = _case_index(manifest, case_id)
    manifest["cases"][idx]["expected_fields"] = []

    error = _schema_error(tmp_path, manifest)

    assert error.validator == "minItems"
    assert list(error.absolute_path) == ["cases", idx, "expected_fields"]


def test_ask_action_requires_ask_clarification(tmp_path: Path):
    """expected_action=ask без ask_clarification"""
    manifest = _load_real_manifest()
    idx = _case_index(manifest, "F01")
    assert manifest["cases"][idx]["expected_action"] == "ask"
    del manifest["cases"][idx]["ask_clarification"]

    error = _schema_error(tmp_path, manifest)

    assert error.validator == "required"
    assert "ask_clarification" in error.message
    assert list(error.absolute_path) == ["cases", idx]


def test_refuse_action_requires_refuse_reason(tmp_path: Path):
    """expected_action=refuse без refuse_reason"""
    manifest = _load_real_manifest()
    idx = _case_index(manifest, "N01")
    assert manifest["cases"][idx]["expected_action"] == "refuse"
    del manifest["cases"][idx]["refuse_reason"]

    error = _schema_error(tmp_path, manifest)

    assert error.validator == "required"
    assert "refuse_reason" in error.message
    assert list(error.absolute_path) == ["cases", idx]
