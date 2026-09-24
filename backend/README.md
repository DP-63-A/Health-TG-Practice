# Backend

Backend на Java 21 и Spring Boot 3.5.6 подключён к общей Gradle-сборке как модуль `backend`.
Используются Spring Web, Spring Security и Spring Data MongoDB. Liquibase входит в согласованный
стек проекта, но пока не подключён.

BE1-02 реализует вход через Telegram Mini App, непрозрачные сессии на 60 минут,
`POST /api/v1/auth/telegram`, `GET /api/v1/me` и общий механизм проверки владельца.

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
.\gradlew.bat :backend:test
.\gradlew.bat :backend:bootJar
.\gradlew.bat :backend:test :backend:bootJar validateContracts :contract-validator:validate --console=plain
```

Интеграционные тесты MongoDB используют Testcontainers и требуют запущенный Docker. При отсутствии
Docker они пропускаются; для итоговой проверки задачи их необходимо выполнить с доступным Docker.

## Запуск

Для запуска нужен доступный MongoDB и обязательные переменные Telegram-конфигурации:

```powershell
.\gradlew.bat :backend:bootRun
```

Общий owner guard предназначен для BE1-04, BE1-05 и BE3-03. Негативные проверки на реальных
маршрутах выполняются после появления этих маршрутов. Java-тесты backend находятся в
`backend/src/test/java`; общие сквозные сценарии относятся к [`tests`](../tests/README.md).
