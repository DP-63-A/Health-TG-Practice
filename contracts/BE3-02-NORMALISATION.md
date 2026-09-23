# BE3-02: pure analytics functions

`AnalyticsFunctions` is a database/UI-independent Java API for BE3-03 and BE1-04.

## Contract

- Input entries are immutable typed records. `current()` and every calculation accept only `CONFIRMED`; `DRAFT`, `CANCELLED`, and `DELETED` are excluded.
- UTC instants are converted with the supplied IANA `ZoneId`; the resulting `LocalDate` includes the current day. Sleep uses `wakeDate` when present. Missing dates, mass, units, and nutrients stay missing and set `incomplete`; no default is guessed.
- Nutrition uses `PER_100G * massGrams / 100`, `PER_SERVING` unchanged, and `UNKNOWN` as incomplete. Unknown nutrient components are not replaced by zero. `countedMeals` counts confirmed meal records in the period.
- Daily steps/sleep select one value per local date by `occurredAt`, then `updatedAt`, then stable `id`. Values are not summed across duplicate daily totals. Sleep unit is minutes. `daysWithData` is the denominator; empty aggregates are `null`.
- Heart rate returns the latest value, instant, entry id, and optional `INSTANT`/`RESTING` qualifier. It does not manufacture a daily average.
- Check-ins are separated by category and retain gaps. Values are not rounded; presentation/API adapters own formatting.

Run the unit and contract checks from the repository root:

```bash
./gradlew :contract-validator:test
./gradlew validateContracts
```

The implementation currently uses fixtures/unit tests only. Database/API integration is intentionally BE3-03's responsibility.
