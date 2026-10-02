#!/usr/bin/env python3
"""
Deterministic synthetic profile generator for BE3-05a.

Generates three 21-day profiles with fixed random seed:
  - regular: stable, no gaps
  - irregular: varied intervals, some cancelled, some changed
  - incomplete: sparse, at least one empty day

Usage:
    python3 generator.py
    python3 generator.py --seed 42 --start-date 2026-09-01
"""

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from random import Random
from typing import Any

# Fixed timezone for all generated times: Europe/Warsaw (UTC+02:00)
TZ = timezone(timedelta(hours=2))


def generate_entry(
    rng: Random,
    date: datetime,
    meal_type: str,
    occurred_offset_hours: float = 0.0,
) -> dict[str, Any]:
    """
    Generate a single food entry.

    Args:
        rng: Random generator with fixed seed
        date: Date of entry (will be converted to datetime at occurrence time)
        meal_type: "breakfast", "lunch", "dinner", "snack"
        occurred_offset_hours: offset from midnight for occurred_at

    Returns:
        Entry dict with food, mass, calories, etc.
    """
    meal_times = {
        "breakfast": 8.5,
        "lunch": 12.5,
        "dinner": 19.0,
        "snack": 15.0,
    }

    base_time = meal_times.get(meal_type, 12.0)
    time_jitter = rng.uniform(-0.5, 0.5)
    occurred_hour = base_time + time_jitter + occurred_offset_hours

    occurred_at = date.replace(hour=int(occurred_hour), minute=int((occurred_hour % 1) * 60), tzinfo=TZ)

    food_options = [
        {"name": "omelet", "mass": rng.randint(180, 240), "kcal": 260, "protein": 18, "carbs": 8, "fat": 17},
        {"name": "salad", "mass": rng.randint(150, 250), "kcal": 120, "protein": 4, "carbs": 8, "fat": 8},
        {"name": "rice_bowl", "mass": rng.randint(280, 350), "kcal": 420, "protein": 16, "carbs": 58, "fat": 12},
        {"name": "soup", "mass": rng.randint(300, 400), "kcal": 180, "protein": 8, "carbs": 20, "fat": 7},
        {"name": "fruit", "mass": rng.randint(100, 200), "kcal": 90, "protein": 1, "carbs": 22, "fat": 0},
    ]

    food_choice = rng.choice(food_options)

    # Scale macros by actual mass vs nominal
    mass_scale = food_choice["mass"] / 200.0
    kcal = int(food_choice["kcal"] * mass_scale)

    return {
        "owner_id": "fake-user-uuid",
        "food": food_choice["name"],
        "mass_g": food_choice["mass"],
        "calories_kcal": kcal,
        "protein_g": round(food_choice["protein"] * mass_scale, 1),
        "carbs_g": round(food_choice["carbs"] * mass_scale, 1),
        "fat_g": round(food_choice["fat"] * mass_scale, 1),
        "occurred_at": occurred_at.isoformat(),
        "status": "confirmed",
        "source": "telegram_text",
    }


def generate_checkin(rng: Random, date: datetime, category: str) -> dict[str, Any]:
    """
    Generate a single state check-in (quick rating).
    """
    categories = ["sleep_quality", "digestion", "mood", "wellbeing"]
    if category not in categories:
        category = rng.choice(categories)

    rating = rng.randint(1, 5)
    hour = rng.randint(20, 23)
    minute = rng.randint(0, 59)

    occurred_at = date.replace(hour=hour, minute=minute, tzinfo=TZ)

    return {
        "owner_id": "fake-user-uuid",
        "category": category,
        "rating": rating,
        "occurred_at": occurred_at.isoformat(),
        "status": "confirmed",
        "source": "telegram_button",
    }


def generate_regular_profile(rng: Random, start_date: datetime) -> dict[str, Any]:
    """
    Regular profile: stable food entries and check-ins, no gaps, no cancellations.
    """
    entries = []

    for day_offset in range(21):
        current_date = start_date + timedelta(days=day_offset)

        # 2-3 food entries per day
        num_meals = rng.randint(2, 3)
        meal_types = rng.sample(["breakfast", "lunch", "dinner", "snack"], k=num_meals)

        for meal_type in meal_types:
            entry = generate_entry(rng, current_date, meal_type)
            entries.append(entry)

        # 1 check-in per day
        checkin = generate_checkin(rng, current_date, rng.choice(["sleep_quality", "digestion", "mood", "wellbeing"]))
        entries.append(checkin)

    return {
        "profile_type": "regular",
        "timezone": "Europe/Warsaw",
        "start_date": start_date.date().isoformat(),
        "entries": entries,
    }


def generate_irregular_profile(rng: Random, start_date: datetime) -> dict[str, Any]:
    """
    Irregular profile: varied intervals, some cancelled drafts, some edits, missing days.
    """
    entries = []

    for day_offset in range(21):
        current_date = start_date + timedelta(days=day_offset)

        # Skip some days entirely (70% chance to have data)
        if rng.random() < 0.3:
            continue

        # Varied number of entries: 0-4
        num_meals = rng.randint(0, 4)

        for _ in range(num_meals):
            meal_type = rng.choice(["breakfast", "lunch", "dinner", "snack"])
            entry = generate_entry(rng, current_date, meal_type, occurred_offset_hours=rng.uniform(-2, 2))

            # Some entries are cancelled drafts (status=cancelled)
            if rng.random() < 0.15:
                entry["status"] = "cancelled"
            # Some are just drafts (status=draft)
            elif rng.random() < 0.1:
                entry["status"] = "draft"

            entries.append(entry)

        # Only ~50% of days have check-ins
        if rng.random() < 0.5:
            checkin = generate_checkin(rng, current_date, rng.choice(["sleep_quality", "digestion", "mood"]))
            entries.append(checkin)

    return {
        "profile_type": "irregular",
        "timezone": "Europe/Warsaw",
        "start_date": start_date.date().isoformat(),
        "entries": entries,
    }


def generate_incomplete_profile(rng: Random, start_date: datetime) -> dict[str, Any]:
    """
    Incomplete profile: sparse data, at least one completely empty day.
    """
    entries = []
    empty_day_offset = rng.randint(0, 20)

    for day_offset in range(21):
        current_date = start_date + timedelta(days=day_offset)

        # Force at least one completely empty day
        if day_offset == empty_day_offset:
            continue

        # Very sparse: 50% of days have any data
        if rng.random() < 0.5:
            continue

        # 0-2 entries when present
        num_meals = rng.randint(0, 2)

        for _ in range(num_meals):
            meal_type = rng.choice(["breakfast", "lunch", "dinner", "snack"])
            entry = generate_entry(rng, current_date, meal_type, occurred_offset_hours=rng.uniform(-1, 1))
            entries.append(entry)

    return {
        "profile_type": "incomplete",
        "timezone": "Europe/Warsaw",
        "start_date": start_date.date().isoformat(),
        "entries": entries,
    }


def generate_all(seed: int = 12345, start_date_str: str = "2026-09-15") -> None:
    """
    Generate all three profiles and save to fixtures/profiles/{profile_type}.json.
    """
    rng = Random(seed)
    start_date = datetime.fromisoformat(start_date_str).replace(tzinfo=TZ)

    profiles = [
        ("regular", generate_regular_profile(rng, start_date)),
        ("irregular", generate_irregular_profile(rng, start_date)),
        ("incomplete", generate_incomplete_profile(rng, start_date)),
    ]

    output_dir = Path(__file__).parent

    for profile_name, profile_data in profiles:
        output_file = output_dir / f"{profile_name}.json"
        with output_file.open("w", encoding="utf-8") as f:
            json.dump(profile_data, f, indent=2, ensure_ascii=False)
        print(f"✓ Generated {output_file.name}: {len(profile_data['entries'])} entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate synthetic profiles for BE3-05a")
    parser.add_argument("--seed", type=int, default=12345, help="Random seed (default: 12345)")
    parser.add_argument("--start-date", type=str, default="2026-09-15", help="Start date YYYY-MM-DD (default: 2026-09-15)")

    args = parser.parse_args()

    generate_all(seed=args.seed, start_date_str=args.start_date)
