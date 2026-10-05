# Backend

Backend на Java 21 и Spring Boot 3.5.6 подключён к общей Gradle-сборке как модуль `:backend:api`.
Используются Spring Web, Spring Security и Spring Data MongoDB. Liquibase входит в согласованный
стек проекта, но пока не подключён.

BE1-02 реализует вход через Telegram Mini App, непрозрачные сессии на 60 минут,
`POST /api/v1/auth/telegram`, `GET /api/v1/me` и общий механизм проверки владельца.
BE1-07 добавляет `/api/v1/healthz`: HTTP 200 при успешном MongoDB ping и контрактный HTTP 503 (`SERVICE_UNAVAILABLE`, `request_id`) при недоступности MongoDB.

## Конфигурация

```text
TELEGRAM_BOT_TOKEN=<secret bot token>
TELEGRAM_ALLOWED_USER_IDS=<comma-separated numeric ids>
MONGODB_URI=mongodb://localhost:27017/health_tg
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

Секреты и реальные Telegram ID нельзя коммитить или включать в отчёты. Значения по умолчанию
для локальной разработки находятся в `src/main/resources/application.properties`.

## Сборка и проверка

Команды выполняются из корня репозитория:

```powershell
.\gradlew.bat :backend:api:test
.\gradlew.bat :backend:api:bootJar
.\gradlew.bat :backend:api:test :backend:api:bootJar validateContracts :contract-validator:validate --console=plain
```

Интеграционные тесты MongoDB используют Testcontainers и требуют запущенный Docker. При отсутствии
Docker они пропускаются; для итоговой проверки задачи их необходимо выполнить с доступным Docker.

## Запуск

Для запуска нужен доступный MongoDB и обязательные переменные Telegram-конфигурации:

```powershell
.\gradlew.bat :backend:api:bootRun
```

Общий owner guard предназначен для BE1-04, BE1-05 и BE3-03. Негативные проверки на реальных
маршрутах выполняются после появления этих маршрутов. Java-тесты backend находятся в
`backend/api/src/test/java`; общие сквозные сценарии относятся к [`tests`](../../tests/README.md).

Linux/macOS: используйте `sh ./gradlew` с теми же задачами. Для Windows с кириллицей в пути
при ошибке загрузки тестового класса добавьте `'-Dorg.gradle.jvmargs=-Dfile.encoding=COMPAT'`.
При прямом запуске через Gradle, JAR или IDEA файлы `.env` автоматически не загружаются;
задайте переменные окружения в терминале или локальной конфигурации IDEA.
В IDEA точка входа — `org.healthtg.HealthTgApplication`,
classpath — `backend.api.main` (возможен префикс имени проекта).

Собранный JAR: `backend/api/build/libs/api-0.1.0-SNAPSHOT.jar`; запуск —
`java -jar backend/api/build/libs/api-0.1.0-SNAPSHOT.jar` из корня репозитория.
API не содержит Telegram polling и может работать независимо от запущенного бота.
## Локальная MongoDB и загрузка .env

[Инструкция окружения](../../docs/local-environment.md) описывает MongoDB в
[Compose](../../compose.yaml), сохранение данных и проверку `/api/v1/healthz`.
Compose запускает только MongoDB. Для API из корня репозитория можно использовать:

```powershell
.\scripts\run-backend.ps1
```

Скрипт явно читает корневой `.env` (образец — [`.env.example`](../../.env.example)),
задаёт переменные в текущем процессе PowerShell и запускает `:backend:api:bootRun`.
Другой файл можно выбрать параметром `-EnvFile <путь>`. Скрипт не запускает бота.
Результат readiness API не подтверждает работу Telegram-бота или Mini App.
## BE3-05: синтетический seed и локальный reset

Три вымышленных 21-дневных профиля (учебные истории, **не** реальные медицинские данные и не модель здоровья).
Запись идёт только через `EntryCoreService` (BE1-03/BE1-04): draft → confirm (`source_kind=seed`, `submission_id`
= устойчивый ключ), правка через `patch`, отмена через `cancel`, быстрые отметки через `createCheckin`.
Seed не заменяет авторизацию и live-проверки Telegram, публичного HTTP endpoint для seed/reset нет.

### Настройка (только окружение, ничего не коммитится)

| Переменная | Назначение |
|---|---|
| `HEALTH_TG_DEMO_ENVIRONMENT=true` | явный признак demo-окружения; по умолчанию `false` — seed и reset отказывают |
| `HEALTH_TG_DEMO_DATABASE` | имя единственной БД, которую разрешено менять; должно совпадать с БД из `MONGODB_URI` |
| `SEED_REGULAR_TELEGRAM_ID`, `SEED_IRREGULAR_TELEGRAM_ID`, `SEED_INCOMPLETE_TELEGRAM_ID` | три разных тестовых аккаунта; каждый обязан входить в `TELEGRAM_ALLOWED_USER_IDS` |
| `SEED_RANDOM_SEED` (по умолчанию `20260916`), `SEED_START_DATE` (по умолчанию `2026-09-14`) | параметры воспроизводимости |

Отказ (код выхода 2) наступает до любых изменений, если нет demo-признака, БД не совпадает с разрешённой,
аккаунт не задан/не в allowlist/повторяется. Привязка профилей только конфигурационная: переключателя профиля в
API нет, пользователь видит только свои записи через обычную авторизацию.

### Команды (из корня репозитория)

```powershell
$env:MONGODB_URI = 'mongodb://localhost:27017/health_tg_demo'
$env:HEALTH_TG_DEMO_DATABASE = 'health_tg_demo'
$env:HEALTH_TG_DEMO_ENVIRONMENT = 'true'
# SEED_*_TELEGRAM_ID и TELEGRAM_ALLOWED_USER_IDS задаются из .env / окружения команды
.\gradlew.bat :backend:api:seedDemo -PseedRandom=20260916 -PseedStartDate=2026-09-14   # создать / повторить
.\gradlew.bat :backend:api:seedDemo -PseedRandom=20260916 -PseedStartDate=2026-09-14   # повтор: ничего не дублируется
.\gradlew.bat :backend:api:resetDemo                                                    # очистка seed-записей
```

`-PseedRandom` и `-PseedStartDate` необязательны. Ключ идемпотентности записи —
`seed:<profile>:<random-seed>:<start-date>` + порядковый номер записи; повтор с теми же параметрами возвращает
существующие записи (в т.ч. при прерванном запуске), другие seed/дата создают отдельный набор —
сначала выполните reset, если нужна замена.

Reset удаляет только записи `entries`, у которых ключ начинается с `seed:` и владелец — один из трёх настроенных
аккаунтов. Пользователи, сессии, состояние диалога, чужие и «обычные» записи тех же аккаунтов не затрагиваются.
Seed не создаёт файлов, поэтому очистка учебных файлов не выполняется; согласование с правилами BE1-05 остаётся
открытым, пока модуль файлов не появится в репозитории.

### Состав наборов (seed 20260916, старт 2026-09-14, Europe/Warsaw)

| Профиль | Подтверждено (питание / показатели / отметки) | Особенности |
|---|---|---|
| regular | 63 / 84 / 84 | ежедневно 3 приёма пищи, сон и пульс покоя утром, шаги и пульс вечером, 4 отметки; исправленная масса (день 6); 3 повторные доставки |
| irregular | 49 / 34 / 33 | 1–4 приёма в разное время, дни без еды (4-й и 11-й), явный 0 шагов (8-й), пропуски показателей; 2 исправленные записи; повторная доставка; 1 отменённый draft |
| incomplete | 13 / 13 / 4 | полностью пустые дни: 2026-09-18, 2026-09-19, 2026-09-27; питание без БЖУ и массы; исправленная масса; повторная доставка; 1 отменённый (неполный) draft метрики |

Единицы: `count` (шаги), `min` (сон), `bpm` (пульс); источники: `seed` и `quick_checkin`; происхождение полей:
`reported` для сообщённого, `estimated` для массы и питательных веществ. Отменённые draft'ы не попадают в
`GET /api/v1/entries` (по умолчанию `confirmed`) и аналитику. Ключи происхождения питательных веществ
плоские (`nutrients`), так как хранилище не принимает ключи с точкой.

Независимые контрольные значения по дням (калории, шаги, сон, число отметок) лежат в
[`fixtures/be3-05/control-values.json`](../../fixtures/be3-05/control-values.json); они зафиксированы по результату
генератора и проверяются тестом, а BE3-04/07 обязаны сверить их независимо.

### Проверка

```powershell
.\gradlew.bat :backend:api:test --tests 'org.healthtg.seed.*'
```

`SyntheticProfileGeneratorTest` проверяет состав и детерминизм, `SeedServiceIntegrationTest` (Docker/Testcontainers)
— повтор seed, выдачу через реальный `GET /api/v1/entries` с сессией, reset только в demo и отказ вне demo.
Приёмка человеком и проверка потребителями BE3-04/07, FE1/FE2 остаются за другим участником.
