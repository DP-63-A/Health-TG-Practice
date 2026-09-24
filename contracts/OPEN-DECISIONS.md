# BE1-01: открытые решения (предложения, не утверждения)

Статус: **PROPOSED** — ждут согласования FE1 / BE2 / BE3. Пока потребитель не подтвердил, значения ниже нельзя считать принятыми.

## Принятые части решений

- **D9 / timezone — ACCEPTED 2026-09-21 (BE1, FE2):** `timezone` является необязательным query-параметром `GET /analytics`; при отсутствии сервер использует `Europe/Warsaw`. FE2 передаёт timezone явно. Подтверждение получено в обсуждении команды; финальная схема ответа `/analytics` остаётся `PROPOSED` до завершения review BE3-01.

Фиксированные ТЗ (не обсуждаются): `Europe/Warsaw` по умолчанию; initData ≤ 15 мин; сессия 60 мин; статусы `draft|confirmed|cancelled|deleted`; типы `meal|metrics|checkin|note`; источники `text|food_photo|health_screenshot|watch_photo|quick_checkin|seed`; origins `reported|extracted|estimated|computed`; префикс `/api/v1`; без публичного create entry.

| # | Вопрос | Вариант A (черновик контракта) | Вариант B | Согласовать с |
|---|---|---|---|---|
| D1 | Пагинация `GET /entries` | `limit` (1–100, default 20) + opaque `cursor`; ответ `{ items, next_cursor }` | `page` + `page_size` + `total` | FE1 |
| D2 | Границы `from` / `to` | Inclusive календарные даты `YYYY-MM-DD` в TZ пользователя; фильтр по локальной дате `occurred_at` | Inclusive ISO-8601 datetime UTC | FE1, BE3 |
| D3 | Чужой/отсутствующий ресурс | Всегда **404** (`RESOURCE_NOT_FOUND`) без утечки существования | 403 чужой / 404 отсутствующий | FE1, BE2 |
| D4 | `If-Match` на `DELETE` | `If-Match: "<revision>"` (ETag = decimal revision в кавычках) | Отдельный заголовок `X-Expected-Revision` | FE1 |
| D5 | Тело auth | `{ session_token, token_type: "Bearer", expires_in: 3600, user }` | `{ access_token, expires_at, user }` | FE1, BE2 |
| D6 | Имена payload meal | `description`, `mass_g`, `nutrients.{energy_kcal,protein_g,fat_g,carbs_g}`, `nutrients_basis` (`per_100g`\|`per_serving`\|`unknown`) | Иные имена полей | BE2, BE3, FE1 |
| D7 | Коды metrics | `steps`, `sleep_duration_min`, `heart_rate` | Расширяемый `string` без enum | BE2, BE3 |
| D8 | Категории checkin | `sleep_quality`, `digestion_comfort`, `wellbeing`, `mood` (ТЗ: качество сна, комфорт пищеварения, самочувствие, настроение) | Короткие коды `sq/dc/wb/md` | BE2, FE1 |
| D9 | Схема `/analytics` | Черновик в `schemas/analytics.json` по §7 ТЗ + FE2 overview | Финальная схема от BE3-01 | BE3, FE2 |
| D10 | Стек backend / проверки | **Java 21 + Gradle** (модуль `tools/contract-validator`, команды `./gradlew validateContracts`), не Python/Maven/FastAPI из базового стека ТЗ | Python/Maven как в общем ТЗ | команда / руководитель |

После согласования: записать сюда `ACCEPTED`, дату, автора и ссылку на обсуждение; обновить OpenAPI одной копией без дублей.
