# BE3-01 — контракт `/analytics`

Статус: **PROPOSED до review FE2 и BE1**. Этот документ фиксирует согласованную между артефактами форму, но не подменяет человеческое утверждение владельцев.

## Затронутые файлы и план

Изменены/добавлены `contracts/schemas/analytics.json`, `contracts/openapi.yaml`, fixtures под `contracts/fixtures/`, `contracts/BE3-01-CONTRACT.md` и `contracts/AnalyticsContractTest.java`. Endpoint, БД и расчётный сервис не добавляются.

## Совместимость с BE1 и FE2

* BE1: используются `confirmed`, типы `meal|metrics|checkin`, идентификатор `entry_id`, локальные даты `YYYY-MM-DD`, IANA timezone и `Europe/Warsaw` как согласованный default политики. Формат авторизации и ошибок остаётся BE1-owned.
* FE2: ответ содержит карточки, `observations.days_with_any_data`, `observations.generated_at`, ровно четыре ряда и выбор `checkin.category`; frontend не вычисляет суммы и средние.
* В текущей ветке BE1 `schemas/analytics.json` помечена DRAFT (D9), поэтому этот контракт нельзя считать ACCEPTED без review BE1/FE2.

## Запрос и даты

`period=today|days_7|days_21`; `timezone` — явная IANA зона. `today` — одна локальная дата; `days_7` и `days_21` включают сегодня и имеют соответственно 7 и 21 локальную календарную дату. `period.from` и `period.to` включительны. Если API сохраняет BE1-политику default, отсутствие timezone означает `Europe/Warsaw`; для контракта FE2 рекомендуется передавать его явно.

## Карточки и null

* nutrition: kcal и граммы Б/Ж/У; `incomplete=true`, если хотя бы один включённый meal имеет неизвестный нутриент. Неизвестное не превращается в 0. `meals_with_energy` — число meal с известной энергией.
* meal_count — количество подтверждённых meal.
* sleep — минуты, дата пробуждения; total и average null при отсутствии данных, знаменатель — только `days_with_data`.
* steps — count; daily total выбирается по `occurred_at`, затем `updated_at`, затем `id`.
* heart_rate — последнее подтверждённое значение с фактическими `occurred_at`, `qualifier` и `entry_id`; контекст не нормализуется в `resting`.
* checkins — последняя оценка каждой категории за день, затем последняя по периоду; отсутствие даёт null score/date/entry_id.

## Четыре ряда

`series.nutrition`, `series.sleep`, `series.steps` и `series.checkin` (выбранная категория). Каждая точка содержит локальную дату, значение, единицу и источник(и). Наблюдение отсутствует в источнике как значение `null`, а не как ноль; в fixtures для пропусков дни присутствуют явно, чтобы FE2 не строил календарь самостоятельно. `source` даёт `entry_id`, тип и локальную дату для перехода к Entry. Единицы: kcal, min, count, score_1_5.

## Синтетические входы и oracle

`analytics_input_records.json` содержит записи и статусы; ожидаемые snapshots — `analytics_normal.json`, `analytics_empty.json`, `analytics_gaps.json`, `analytics_dedup.json`, `analytics_filtered.json`. Числа заданы независимо от будущей реализации:

* 165 kcal/100g × 200g = 330 kcal; до записи 600, итого 930.
* изменение массы до 150g даёт 247.5 kcal; 600 + 247.5 = 847.5; после logical delete — 600; старая revision не суммируется.
* 3000 в 12:00 и 5000 в 20:00 дают дневное значение 5000, не 8000.
* 420 и 480 минут дают total 900, average 450, days_with_data 2.
* draft/cancelled/deleted отсутствуют в sources и расчётах.

## Проверки и ограничения

`AnalyticsContractTest` проверяет структуру fixture и независимые numeric assertions в fixture-only режиме. Реальная интеграция с БД/endpoint не входит в BE3-01 и остаётся блокировкой до BE3-02/BE3-03/BE1-01. Человеческий review FE2 и BE1 в этом коммите отсутствует и поэтому AC4–AC6 не объявляются пройденными.
