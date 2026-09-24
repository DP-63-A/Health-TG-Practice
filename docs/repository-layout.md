# Структура репозитория

## Текущая организация

```text
Health-TG-Practice/
|-- README.md
|-- build.gradle.kts
|-- settings.gradle.kts
|-- gradlew / gradlew.bat
|-- gradle/wrapper/
|-- backend/                  Два отдельных запуска одного Java-приложения
|   |-- api/                  HTTP API и его тесты
|   `-- bot/                  Telegram-бот и его тесты
|-- frontend/                 Telegram Mini App (пока документация)
|-- contracts/                OpenAPI, JSON-схемы, примеры и отчёты
|-- tools/contract-validator/ Java-инструмент проверки контрактов
|-- fixtures/                 общие синтетические данные
|-- tests/                    сквозные проверки продукта
`-- docs/                     проектная и техническая документация
```

Рабочий процесс, зависимости, решения, правила проверки и исходный PDF находятся в корне.
Ссылки на них собраны в [карте документации](README.md).

## Gradle-модули

Корневой `settings.gradle.kts` подключает:

- `backend:api` — Spring Boot 3.5.6, Java 21, HTTP API, безопасность и MongoDB persistence;
- `backend:bot` — Spring Boot 3.5.6, Java 21, TelegramBots, без HTTP-сервера и MongoDB;
- `contract-validator` — проверка OpenAPI, JSON-схем и контрактных примеров.

Версия Gradle задаётся Wrapper: 8.11.1. Все Java-модули используют toolchain Java 21.
Frontend пока не подключён к сборке.

## Код и проверки

Код API находится в `backend/api/src/main/java`, код бота — в `backend/bot/src/main/java`; тесты каждого приложения находятся рядом в `src/test/java`.
Код валидатора находится в `tools/contract-validator/src/main/java`, его тесты — в соседнем
`src/test/java`. Корневой каталог `tests/` предназначен для будущих сквозных сценариев.

Контрактные примеры находятся в `contracts/examples`, аналитические fixture — в
`contracts/fixtures`, а каталог `fixtures/` зарезервирован для общих синтетических наборов продукта.

Команды текущей сборки и порядок открытия в IntelliJ IDEA приведены в
[корневом README](../README.md). Конфигурация и запуск backend описаны в
[`backend/README.md`](../backend/README.md).