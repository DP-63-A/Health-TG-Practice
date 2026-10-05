"""Pytest configuration for visual_cases validation tests"""

import pytest
from pathlib import Path
import json


@pytest.fixture(scope="session")
def manifest_path():
    """Return path to manifest.json"""
    return Path(__file__).parent / 'manifest.json'


@pytest.fixture(scope="session")
def schema_path():
    """Return path to manifest-schema.json"""
    return Path(__file__).parent / 'manifest-schema.json'


@pytest.fixture(scope="session")
def manifest_data(manifest_path):
    """Load manifest.json"""
    with open(manifest_path, 'r', encoding='utf-8') as f:
        return json.load(f)


@pytest.fixture(scope="session")
def schema_data(schema_path):
    """Load manifest-schema.json"""
    with open(schema_path, 'r', encoding='utf-8') as f:
        return json.load(f)


@pytest.fixture(scope="session")
def base_dir():
    """Return base directory for visual_cases"""
    return Path(__file__).parent
