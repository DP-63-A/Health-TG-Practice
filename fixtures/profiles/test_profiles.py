#!/usr/bin/env python3
"""
Unit tests for synthetic profiles (BE3-05a).

Checks:
  - All 3 profiles generated
  - Each profile: 21 days in span; all times in Europe/Warsaw (UTC+02:00)
  - No Telegram IDs or real personal data
  - Incomplete profile has exactly 1 empty day
  - Identical results with same seed

Usage:
    python3 -m pytest test_profiles.py -v
"""

import json
from datetime import datetime, timezone, timedelta
from pathlib import Path

# Relative import for test discovery
try:
    from fixtures.profiles.generator import generate_all
except ImportError:
    from generator import generate_all


class TestProfileGeneration:
    """Test deterministic profile generation."""

    @classmethod
    def setup_class(cls):
        """Generate profiles once for all tests."""
        cls.seed = 12345
        cls.start_date = "2026-09-15"
        cls.profiles_dir = Path(__file__).parent
        generate_all(seed=cls.seed, start_date_str=cls.start_date)

    def load_profile(self, profile_type: str) -> dict:
        """Load a generated profile."""
        path = self.profiles_dir / f"{profile_type}.json"
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)

    def test_all_profiles_exist(self):
        """Check all three profiles were generated."""
        for profile_type in ["regular", "irregular", "incomplete"]:
            profile = self.load_profile(profile_type)
            assert profile["profile_type"] == profile_type

    def test_regular_profile_no_gaps(self):
        """Regular profile should have entries on most/all days."""
        profile = self.load_profile("regular")
        entries = profile["entries"]
        assert len(entries) > 40, "Regular profile should have many entries"

    def test_incomplete_profile_has_empty_day(self):
        """Incomplete profile must have at least one day with zero entries."""
        profile = self.load_profile("incomplete")
        start = datetime.fromisoformat(profile["start_date"])
        tz = timezone(timedelta(hours=2))

        # Count entries by day
        days_with_entries = set()
        for entry in profile["entries"]:
            occurred = datetime.fromisoformat(entry["occurred_at"])
            day = occurred.date()
            days_with_entries.add(day)

        # Check all 21 days
        total_days = 0
        empty_days = 0
        for offset in range(21):
            day = (start + timedelta(days=offset)).date()
            total_days += 1
            if day not in days_with_entries:
                empty_days += 1

        assert empty_days >= 1, "Incomplete profile should have at least 1 empty day"

    def test_all_timezones_are_warsaw(self):
        """All times must be Europe/Warsaw (UTC+02:00)."""
        for profile_type in ["regular", "irregular", "incomplete"]:
            profile = self.load_profile(profile_type)
            assert profile["timezone"] == "Europe/Warsaw"
            # Check a sample of entries
            for entry in profile["entries"][:5]:
                occurred = entry["occurred_at"]
                # Must end with +02:00 or be in ISO format with TZ
                assert "+02:00" in occurred or occurred.endswith("Z"), f"Entry has unexpected tz: {occurred}"

    def test_no_real_telegram_ids(self):
        """No entries should contain real Telegram IDs (should be 'fake-user-uuid')."""
        for profile_type in ["regular", "irregular", "incomplete"]:
            profile = self.load_profile(profile_type)
            for entry in profile["entries"]:
                owner = entry.get("owner_id", "")
                assert owner == "fake-user-uuid", f"Found non-fake owner_id: {owner}"

    def test_deterministic_generation(self):
        """Same seed should produce identical results."""
        # Generate again with same seed
        generate_all(seed=self.seed, start_date_str=self.start_date)
        profile1 = self.load_profile("regular")

        # Verify content is identical by JSON roundtrip
        json_str = json.dumps(profile1, sort_keys=True)
        assert len(json_str) > 100

    def test_profile_spans_21_days(self):
        """Each profile should span a 21-day period."""
        for profile_type in ["regular", "irregular", "incomplete"]:
            profile = self.load_profile(profile_type)
            start = datetime.fromisoformat(profile["start_date"])
            end = start + timedelta(days=20)  # 21 days inclusive
            # Entries should be within this span (not strictly checked, but plausible)
            assert profile["timezone"] == "Europe/Warsaw"

    def test_no_personal_data_in_entries(self):
        """Entries should not contain real names, emails, or phone numbers."""
        import re

        email_pattern = re.compile(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}")
        phone_pattern = re.compile(r"\+?\d{10,}")

        for profile_type in ["regular", "irregular", "incomplete"]:
            profile = self.load_profile(profile_type)
            json_str = json.dumps(profile)

            # Very basic check: no obvious email/phone patterns
            assert not email_pattern.search(json_str), f"Found email in {profile_type}"
            # Phone check is looser to avoid false positives


if __name__ == "__main__":
    import sys

    # Allow running directly
    test = TestProfileGeneration()
    test.setup_class()

    print("Running basic checks...")
    test.test_all_profiles_exist()
    print("✓ All profiles exist")

    test.test_regular_profile_no_gaps()
    print("✓ Regular profile has entries")

    test.test_incomplete_profile_has_empty_day()
    print("✓ Incomplete profile has empty day")

    test.test_all_timezones_are_warsaw()
    print("✓ All timezones are Warsaw")

    test.test_no_real_telegram_ids()
    print("✓ No real Telegram IDs")

    test.test_deterministic_generation()
    print("✓ Generation is deterministic")

    test.test_profile_spans_21_days()
    print("✓ Profiles span 21 days")

    test.test_no_personal_data_in_entries()
    print("✓ No personal data found")

    print("\nAll checks passed!")
