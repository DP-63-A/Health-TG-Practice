# Synthetic profiles dataset (BE3-05a)

Deterministic JSON fixtures for three 21-day user profiles used across BE3 tests and integration.

## Profiles

1. **regular** — stable food entries without gaps; consistent sleep and check-ins
2. **irregular** — varied intervals, some cancelled drafts, changed entries; missing days
3. **incomplete** — sparse data; at least one completely empty day

## Files

- `generator.py` — Python 3.9+ generator module with deterministic RNG
- `{profile_type}.json` — static fixtures for use in tests
- `test_profiles.py` — unit tests validating invariants

## Generation

```bash
python3 fixtures/profiles/generator.py
```

Custom seed:

```bash
python3 -c "from fixtures.profiles.generator import generate_all; generate_all(seed=42, start_date_str='2026-09-01')"
```

## Validation

```bash
python3 -m pytest fixtures/profiles/test_profiles.py -v
```

Invariants checked:

- All 3 profiles generated
- Each profile: 21 days; all times UTC+02:00 (Europe/Warsaw)
- No Telegram IDs or real personal data
- Incomplete profile has exactly 1 empty day
- Identical results with same seed
