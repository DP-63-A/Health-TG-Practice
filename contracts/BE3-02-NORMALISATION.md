# BE3-02: pure analytics functions

`AnalyticsFunctions` is a database/UI-independent Java API for BE3-03 and BE1-04.

## Contract

- Input entries are immutable typed records.
- Only entries with status `CONFIRMED` participate in calculations.
- Entries with status `DRAFT`, `CANCELLED`, or `DELETED` are excluded.
- `dailyMetric` and `latestHeartRate` accept only entries with type `metrics`.
- `checkins` accepts only entries with type `checkin`.
- `nutrition` accepts only entries with type `meal`.
- UTC instants are converted with the supplied IANA `ZoneId`.
- Sleep is assigned to `wakeDate`.
- Missing dates, mass, units, and nutrients are not guessed and set `incomplete` where applicable.
- Nutrition uses `PER_100G * massGrams / 100`.
- `PER_SERVING` values are not scaled by mass.
- Unknown nutrient components are not replaced by zero.
- Daily metrics select one value per local date by `occurredAt`, then `updatedAt`, then stable `id`.
- Daily values are not summed across duplicate records.
- Sleep is measured in minutes.
- `daysWithData` is used as the average denominator.
- Empty aggregates contain `null` values.
- Heart rate returns the latest value with its timestamp, qualifier, and entry id.
- Check-ins are grouped by category and retain gaps.
- Presentation formatting is not performed by this module.

## Verification command

The complete verification command is:

```bash
./gradlew clean :analytics:test validateContracts :contract-validator:validate --no-daemon --console=plain
```

On Windows:

```powershell
.\gradlew.bat clean :analytics:test validateContracts :contract-validator:validate --no-daemon --console=plain
```

## Acceptance criteria report

| AC | Команда / проверка | Ожидаемый результат | Фактический результат | Статус | Доказательство |
|---|---|---|---|---|---|
| AC1 | `:analytics:test` | Модуль компилируется, unit-тесты проходят | Заполнить после запуска команды | Не проверен до запуска | `core/analytics/src/main/java/com/health/analytics/AnalyticsFunctions.java` |
| AC2 | `:analytics:test` | Фильтруются `DRAFT`, `CANCELLED`, `DELETED`; учитываются только подходящие типы | Заполнить после запуска команды | Не проверен до запуска | `AnalyticsFunctionsTest` |
| AC3 | `:analytics:test` | Проверены значения `930`, `847.5`, `600`, `5000`, `900`, `450` | Заполнить после запуска команды | Не проверен до запуска | `AnalyticsFunctionsTest` |
| AC4 | `validateContracts` | Контрактные fixtures проходят проверку | Заполнить после запуска команды | Не проверен до запуска | `tools/contract-validator/src/test/java/com/health/analytics/contracts/AnalyticsContractTest.java` |
| AC5 | `:contract-validator:validate` | Валидатор контрактов завершается успешно | Заполнить после запуска команды | Не проверен до запуска | Результат Gradle-команды |
