# Contracts (BE1-01)

Единый контракт: OpenAPI, JSON Schema, примеры. Реализация API/БД здесь не делается.

## Структура

| Путь | Назначение |
|---|---|
| `openapi.yaml` | Публичный HTTP API `/api/v1` |
| `internal.yaml` | Внутренние `create_draft` / `create_checkin` / `list_confirmed_entries` |
| `schemas/` | JSON Schema (User, Entry, payloads, Error, Analytics) |
| `examples/valid/` | Положительные примеры |
| `examples/invalid/` | Отрицательные + причины в `_rejection` / README |
| `rules/runtime-invariants.md` | Owner / revision / повторы (вне схемы) |
| `OPEN-DECISIONS.md` | Предложения по открытым решениям ТЗ |

`openapi.yaml` ссылается на JSON-fixtures через `Example Object.externalValue`; валидатор проверяет существование и JSON-синтаксис этих файлов. Примеры `entry-draft-meal.json` и `entry-metrics-zero-steps.json` показывают различие между неизвестным `null` и явно сообщённым `0`.

`examples/valid/analytics-days7.json` — синтетический ответ для проверки формы данных. Его `sources` перечисляет условные записи, включая записи, для которых нет отдельных файлов Entry в этом каталоге. Это не результат работающего API или независимая проверка расчётов BE3.

## Проверка (Gradle / Java)

Из корня репозитория:

```bash
./gradlew validateContracts
./gradlew :contract-validator:validate
```

Windows:

```bat
gradlew.bat validateContracts
gradlew.bat :contract-validator:validate
```

Требуется JDK 21.
