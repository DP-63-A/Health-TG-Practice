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

Because the repository's `gradlew` currently has mode `100644` and is not executable, use `sh` on Linux/macOS:

```bash
sh ./gradlew clean :analytics:test validateContracts :contract-validator:validate --no-daemon --console=plain
```

On Windows:

```powershell
.\gradlew.bat clean :analytics:test validateContracts :contract-validator:validate --no-daemon --console=plain
```

## Acceptance criteria report

The command was run successfully on commit `74cc7ae9cc9986e092e2ddb39d20bb180a6ec27f` using Java 26. The run completed in 56 seconds and reported `12 actionable tasks: 12 executed`.

The Gradle output did not print a JUnit test count, so this report records the verified task result rather than inventing a test number.

| AC | Команда / проверка | Ожидаемый результат | Фактический результат | Статус | Доказательство |
|---|---|---|---|---|---|
| AC1 | `:analytics:test` | Модуль компилируется, unit-тесты проходят | `:analytics:test` завершился успешно | Passed | Gradle output; commit `74cc7ae9cc9986e092e2ddb39d20bb180a6ec27f` |
| AC2 | `:analytics:test` | Фильтруются `DRAFT`, `CANCELLED`, `DELETED`; учитываются только подходящие типы | Analytics unit-тесты завершились успешно | Passed | `AnalyticsFunctionsTest`; commit `74cc7ae9cc9986e092e2ddb39d20bb180a6ec27f` |
| AC3 | `:analytics:test` | Проверены значения `930`, `847.5`, `600`, `5000`, `900`, `450` | Analytics unit-тесты завершились успешно | Passed | `AnalyticsFunctionsTest`; commit `74cc7ae9cc9986e092e2ddb39d20bb180a6ec27f` |
| AC4 | `validateContracts` | Контрактные fixtures проходят проверку | `:validateContracts` завершился успешно | Passed | Gradle output: `> Task :validateContracts`; commit `74cc7ae9cc9986e092e2ddb39d20bb180a6ec27f` |
| AC5 | `:contract-validator:validate` | Валидатор контрактов завершается успешно | `:contract-validator:validate` завершился успешно; OpenAPI и examples validated | Passed | Gradle output: `OK: OpenAPI + examples validated ...`; commit `74cc7ae9cc9986e092e2ddb39d20bb180a6ec27f` |

Примечание: предупреждения `SLF4J(W)` относятся к отсутствующему SLF4J provider и не повлияли на результат проверки. Итоговый результат сборки: `BUILD SUCCESSFUL`.
