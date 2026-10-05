import io
import pytest
from pathlib import Path
from PIL import Image
from validate_manifest import validate_image_format, validate_image_integrity, validate_image_resolution, validate_manifest

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

def test_manifest_validation_fails_on_missing_required_field(tmp_path: Path):
    broken = tmp_path / "broken.json"
    broken.write_text('{"cases":[{"id":"X1","image_path":"watch/W02.jpeg","expected_fields":[{"name":"steps"}]}]}', encoding="utf-8")

    with pytest.raises(Exception) as exc:
        validate_manifest(str(broken))

    msg = str(exc.value)
    assert "required" in msg.lower()
    assert "value" in msg.lower() or "unit" in msg.lower() or "confidence" in msg.lower()
