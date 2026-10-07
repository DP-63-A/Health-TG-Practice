# Backend

Backend на Java 21 и Spring Boot 3.5.6 подключён к общей Gradle-сборке как модуль `:backend:api`.
Используются Spring Web, Spring Security и Spring Data MongoDB. Liquibase входит в согласованный
стек проекта, но пока не подключён.

BE1-02 реализует вход через Telegram Mini App, непрозрачные сессии на 60 минут,
`POST /api/v1/auth/telegram`, `GET /api/v1/me` и общий механизм проверки владельца.
BE1-07 добавляет `/api/v1/healthz`: HTTP 200 при успешном MongoDB ping и контрактный HTTP 503 (`SERVICE_UNAVAILABLE`, `request_id`) при недоступности MongoDB.

BE1-05 добавляет защищённый `GET /api/v1/files/{id}`. Файл выбирается одновременно по UUID и владельцу текущей сессии; чужой и неизвестный UUID дают одинаковый 404. Оригиналы и правила внутренней загрузки описаны в [документации BE1-05](../../docs/BE1-05-FILE-STORAGE.md).

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

Интеграционные тесты MongoDB используют Testcontainers и требуют запущенный Docker. Задача
`be1Acceptance` намеренно не пропускает обязательное доказательство при недоступном Docker.

## Запуск

Для запуска нужен доступный MongoDB и обязательные переменные Telegram-конфигурации:

```powershell
.\gradlew.bat :backend:api:bootRun
```

Owner guard применяется к entry, file и analytics маршрутам. Негативные проверки отсутствующей,
просроченной и чужой сессии выполняются через реальные HTTP-маршруты. Java-тесты backend находятся
в `backend/api/src/test/java`; общие сквозные сценарии описаны в [`tests`](../../tests/README.md).

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
Основной Compose запускает MongoDB, API, bot и frontend. Для host-side разработки
`compose.dev.yaml` публикует MongoDB только на loopback. API из корня репозитория запускается так:

```powershell
.\scripts\run-backend.ps1
```

Скрипт явно читает корневой `.env` (образец — [`.env.example`](../../.env.example)),
задаёт переменные в текущем процессе PowerShell и запускает `:backend:api:bootRun`.
Другой файл можно выбрать параметром `-EnvFile <путь>`. Скрипт не запускает бота.
Результат readiness API не подтверждает работу Telegram-бота или Mini App.
