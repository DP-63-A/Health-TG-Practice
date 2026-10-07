# BE3-01 — схема контракта `/analytics`

## Статус

**СОГЛАСОВАНО — структура контракта подтверждена BE1 и FE2.**

Документ описывает фактическое состояние артефактов BE3-01 в `develop` после merge PR #49 (`9c05a313b013523f96165587f6de570b01a3cc1c`). Контракт подготовлен в fixture-only режиме: реальная интеграция endpoint и БД в эту задачу не входит.

## Основание и ответственность

- Задача: **BE3-01**, Issue #10.
- Владелец формата: `@DP-63-A` (BE3).
- Потребитель и проверяющий отображение: FE2.
- Проверка включения в общее API, авторизации и совместимости с Entry: BE1.
- Основание: ТЗ v1.0 от 16.09.2026, FR-08–10, FR-12, разделы 4–7 и 10, приложение BE-3.
- Реальная реализация endpoint, расчётов и БД относится к последующим задачам.

## Текущее состояние и затронутые файлы

В `develop` присутствует fixture-only версия контракта, примеры и независимый oracle:

- `contracts/openapi.yaml` — общий OpenAPI BE1 с endpoint `/analytics`, параметрами периода, timezone и `checkin_category`.
- `contracts/schemas/analytics.json` — JSON Schema ответа аналитики.
- `contracts/examples/valid/analytics-days7.json` — валидный общий OpenAPI-пример ответа за 7 дней.
- `contracts/fixtures/analytics_input_normal.json` — обычный набор входных записей.
- `contracts/fixtures/analytics_input_gaps.json` — входные записи с пропусками сна.
- `contracts/fixtures/analytics_input_dedup.json` — два дневных итога шагов для проверки выбора последнего.
- `contracts/fixtures/analytics_input_filtered.json` — набор для проверки фильтрации статусов.
- `contracts/fixtures/analytics_input_mutation.json` — изменение массы и logical delete записи.
- `contracts/fixtures/analytics_expected_normal.json` — обычный ожидаемый ответ.
- `contracts/fixtures/analytics_expected_empty.json` — ожидаемый ответ полностью пустого периода.
- `contracts/fixtures/analytics_expected_gaps.json` — ожидаемый ответ с пропусками сна.
- `contracts/fixtures/analytics_expected_dedup.json` — ожидаемый ответ с выбранным дневным итогом шагов.
- `contracts/fixtures/analytics_expected_filtered.json` — ожидаемый ответ после исключения неподходящих статусов.
- `contracts/fixtures/analytics_expected_mass_changed.json` — независимое ожидание `847.5` ккал после изменения массы.
- `tools/contract-validator/src/test/java/com/health/analytics/contracts/AnalyticsContractTest.java` — проверки численных ожиданий, null-семантики, схемы, источников и единиц.

Старое имя `contracts/fixtures/analytics_input_records.json` в текущем дереве не используется: входные данные разделены по сценариям `analytics_input_normal.json`, `analytics_input_gaps.json`, `analytics_input_dedup.json`, `analytics_input_filtered.json` и `analytics_input_mutation.json`.

## Совместимость с BE1 и FE2

### Общий OpenAPI

`contracts/openapi.yaml` содержит общий контракт BE1, а не отдельную урезанную копию BE3. Серверный префикс `/api/v1` задан в `servers`, поэтому endpoint в разделе `paths` имеет имя `/analytics`.

Параметры `GET /api/v1/analytics`:

- `period` — обязательный enum `today | days_7 | days_21`;
- `timezone` — необязательный IANA timezone, default `Europe/Warsaw`;
- `checkin_category` — необязательная категория состояния, задаваемая через общий `CheckinCategory`.

В YAML используется один корневой блок `components`, содержащий `securitySchemes`, `parameters` и `responses`. Авторизация и стандартные ошибки используют общие определения BE1.

Ссылка на ответ схемы — `./schemas/analytics.json`.

### Entry и идентификаторы

Контракт использует правила BE1:

- учитываются только актуальные записи со статусом `confirmed`;
- `draft`, `cancelled` и `deleted` не участвуют в расчётах, рядах и `sources`;
- `entry_id` и `id` в примерах имеют UUID-формат;
- источник содержит `entry_id`, тип записи и локальную дату для перехода к Entry;
- пульс содержит `entry_id`, `local_date`, `local_time`, исходный момент сообщения `occurred_at` и `qualifier`.

### Данные для FE2

Ответ содержит необходимые элементы overview:

- карточки питания, количества приёмов пищи, сна, шагов, пульса и четырёх оценок;
- `observations.days_in_period`, `observations.days_with_any_data` и `observations.generated_at`;
- ряды `nutrition`, `sleep`, `steps` и `checkin`;
- выбор категории через `series.checkin.category` и query-параметр `checkin_category`.

Frontend не должен самостоятельно пересчитывать суммы, средние, выбор последнего дневного значения или фильтрацию статусов.

## Запрос периода и локальные даты

Период задаётся параметром:

```text
period=today | days_7 | days_21
```

Границы периода являются локальными календарными датами в указанном IANA timezone, сегодняшний день включается:

- `today` — одна локальная дата;
- `days_7` — 7 локальных календарных дат, включая сегодня;
- `days_21` — 21 локальная календарная дата, включая сегодня.

Если `timezone` не передан, применяется `Europe/Warsaw`. В ответе `period.timezone` обязателен и содержит фактически использованную зону.

`observations.generated_at` — UTC timestamp формирования snapshot ответа. Это не время последней исходной записи и не значение, которое frontend вычисляет самостоятельно.

## Карточки и значения `null`

### Питание

`cards.nutrition` содержит `energy_kcal`, `protein_g`, `fat_g`, `carbs_g`, `incomplete` и `meals_with_energy`.

Неизвестный нутриент не превращается в ноль. При полном отсутствии питания числовые итоги равны `null`, а не `0`. `incomplete` показывает, что у включённого meal отсутствует хотя бы один нутриент. `meals_with_energy` считает meal с известной энергией.

### Количество приёмов пищи

`cards.meal_count.count` — количество подтверждённых meal в периоде. Ноль означает отсутствие подтверждённых meal.

### Сон

`cards.sleep` содержит `total_minutes`, `average_minutes` и `days_with_data`. Сон измеряется в минутах и относится к локальной дате пробуждения. Среднее считается только по дням, для которых есть данные.

### Шаги

`cards.steps` содержит `total`, `average` и `days_with_data`. Реальный ноль шагов сохраняется как числовой ноль; отсутствие измерения выражается через `null`.

### Пульс

`cards.heart_rate` содержит подтверждённое значение `value_bpm`, `local_date`, `local_time`, исходный момент сообщения `occurred_at`, `qualifier` и `entry_id`. Выбирается самая поздняя дата измерения в периоде, внутри неё последнее сообщение по `occurred_at → updated_at → id`. При отсутствии измерения все шесть полей равны `null`; неизвестное время измерения остаётся `null`.

### Оценки

`cards.checkins` содержит объект для каждой категории:

- `sleep_quality`;
- `digestion_comfort`;
- `wellbeing`;
- `mood`.

Каждый объект содержит `score`, локальную `date` и `entry_id`. При отсутствии оценки все три поля равны `null`.

## Четыре ряда

### `series.nutrition`

Точка содержит `date`, `energy_kcal` и массив `source`, поскольку дневная сумма может состоять из нескольких meal. `energy_kcal: null` означает отсутствие подтверждённых данных питания за дату; для nutrition пропуск представлен точкой с пустым массивом `source`.

### `series.sleep`

Точка содержит `date`, `value`, `unit: "min"` и `source`. Для пропуска `value` и `source` равны `null`.

### `series.steps`

Точка содержит `date`, `value`, `unit: "count"` и источник выбранного дневного итога. Дневные итоги не складываются между собой.

### `series.checkin`

Ряд содержит выбранную категорию и точки с `value`, `unit: "score_1_5"` и `source`. Для пропуска `value` и `source` равны `null`.

## Правила отбора и расчёта

1. В аналитику попадают только актуальные `confirmed` entries.
2. `draft`, `cancelled` и `deleted` исключаются.
3. Для повторных дневных итогов шагов и сна выбирается запись по `occurred_at`, затем по `updated_at`, затем по `id`.
   Для шагов, сна и пульса день определяется только по `payload.local_date` (`Entry.localDate` для шагов и пульса, `Entry.wakeDate` для сна в функциях; оба поля получаются из `payload.local_date`), а `occurred_at` содержит полный момент сообщения итога. Отсутствующая дата не выводится из timestamp; такая запись не участвует в дневном расчёте. Для сна это дата пробуждения, для пульса — дата измерения; правка даты не меняет исходный `occurred_at`. Подробнее: [runtime-invariants.md](rules/runtime-invariants.md#дневные-итоги-шагов).
4. Дневные итоги шагов не складываются: для даты используется выбранное последнее актуальное значение.
5. Среднее считается по дням с данными, а не по всем календарным дням периода.
6. Сон измеряется в минутах и относится к дате пробуждения.
7. Пульс передаётся с фактическим контекстом исходного Entry.
8. Для каждой категории состояния выбирается последняя оценка за день; для карточки используется последняя доступная оценка за период.
9. Наличие, ноль и неполная сумма питания различаются через `null`, числовой ноль и `incomplete`.
10. Каждая непустая точка содержит источник для перехода к Entry; у пропуска источник равен `null` либо пустому массиву для nutrition.

## Синтетические входы и независимые ожидания

Численные ожидания заданы независимо от будущей реализации расчётов:

- 165 ккал на 100 г при массе 200 г дают 330 ккал; при предыдущих 600 ккал сумма равна 930.
- После изменения массы той же записи на 150 г новая энергия равна 247,5 ккал; сумма равна 847,5 (`analytics_expected_mass_changed.json`).
- После logical delete изменённой записи остаётся 600 ккал; старая версия не учитывается повторно.
- Два дневных итога шагов 3000 в 12:00 и 5000 в 20:00 дают за дату 5000, а не 8000.
- Сон за две даты 420 и 480 минут даёт total 900, average 450 и `days_with_data = 2`.
- При отсутствии подтверждённых записей итоговые измерения и источники пусты или `null`; отсутствие не подменяется нулём.

## Проверки

`tools/contract-validator/src/test/java/com/health/analytics/contracts/AnalyticsContractTest.java` проверяет:

- независимые численные ожидания 930, 900, 450, 5000, 600, 300 и 847.5;
- отличие `null` от нуля в пустом периоде;
- соответствие ожидаемых snapshot-файлов `contracts/schemas/analytics.json`;
- наличие навигационных источников в непустых данных;
- отклонение неверных единиц и отсутствующих источников;
- допустимые zone IDs, включая `UTC`, `America/Port-au-Prince` и `Etc/GMT+3`.

Проверки относятся к fixture-only режиму. Фактический запуск Java 21/Gradle должен фиксироваться отдельно для конкретного commit, например:

```bash
./gradlew validateContracts
```

или:

```bash
./gradlew :contract-validator:validate --offline --console=plain
```

## Отчёт по критериям приёмки

| Критерий | Фактическое состояние в `develop` | Статус | Доказательство |
|---|---|---|---|
| AC1 | Контракт покрывает карточки, четыре ряда, периоды, единицы, источники и неполноту. | **Пройдено** | `contracts/schemas/analytics.json`, `contracts/openapi.yaml`, fixtures и OpenAPI example |
| AC2 | Правила выбора дневного значения, среднее по дням с данными и пропуски зафиксированы; численные oracle соответствуют ТЗ. | **Пройдено** | `analytics_input_*.json`, `analytics_expected_*.json`, `AnalyticsContractTest` |
| AC3 | Подготовлены входы и ожидаемые ответы для normal, empty, gaps, dedup, filtered и mutation; отдельный oracle — `analytics_expected_mass_changed.json`. | **Пройдено** | `contracts/fixtures/` и тест `expectedNumbersAreIndependent` |
| AC4 | Схема и fixture-клиентские данные подготовлены; запуск Gradle и его лог должны быть приложены отдельно, если выполнялись. | **Подготовлено; запуск не зафиксирован в этом документе** | `AnalyticsContractTest.java` |
| AC5 | Схема включена в общий OpenAPI BE1 через `./schemas/analytics.json`; структура согласована с BE1. | **Пройдено** | `contracts/openapi.yaml`, `/analytics`, общие `components` |
| AC6 | Структура, источники, точки рядов и данные для overview согласованы с BE1 и FE2. | **Пройдено** | Согласование BE1/FE2; PR #49 |

## Границы и оставшиеся действия

Согласование контракта BE1/FE2 завершено. Не являются блокировками BE3-01, но должны выполняться в следующих задачах или проверках:

1. запуск Java 21/Gradle-валидатора на проверяемом commit с приложением лога;
2. реализация расчётов и endpoint с реальной БД — BE3-02/03;
3. полный seed — BE3-05;
4. fixture-клиент и экран overview — FE2-01/02.

Наличие fixture-файлов само по себе не означает готовность реального endpoint или БД.

## Передача результата

Результат передаётся для:

- BE1-01 — общий OpenAPI, авторизация и ошибки;
- FE2-01/02 — fixture-клиент и overview без frontend-пересчёта итогов;
- BE3-02/03/05 — реализация расчётов, интеграция и seed.

Изменения относятся к Issue #10 и проверенному merge commit `9c05a313b013523f96165587f6de570b01a3cc1c`.
