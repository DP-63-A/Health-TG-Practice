# Backend

Backend состоит из двух отдельных приложений одной Gradle-сборки на Java 21 и Spring Boot 3.5.6:

| Модуль | Назначение | Инструкция |
|---|---|---|
| `:backend:api` | HTTP API: Telegram-аутентификация, сессии, `/api/v1/me`, проверка владельца, MongoDB persistence и readiness `/api/v1/healthz` | [API](api/README.md) |
| `:backend:bot` | Telegram-бот: long polling, закрытый доступ, `/start`, `/state`, кнопки | [Бот](bot/README.md) |

У каждого приложения свои зависимости, `application.properties`, запускаемый JAR и процесс.
Между модулями нет зависимости: запуск API не запускает Telegram polling; бот не запускает
HTTP-сервер и не подключается к MongoDB. Общие классы пока не выделены: совместное хранение
и сценарий сохранения отметок относятся к следующим задачам. Liquibase пока не подключён.

## Сборка и тесты

Из корня репозитория на Java 21:

```powershell
.\gradlew.bat :backend:api:test :backend:bot:test :backend:api:bootJar :backend:bot:bootJar
```

Linux/macOS: те же задачи с `sh ./gradlew` вместо `.\gradlew.bat`.
Для Windows с кириллицей в пути добавьте `'-Dorg.gradle.jvmargs=-Dfile.encoding=COMPAT'`.
MongoDB-интеграционные тесты API требуют Docker; тесты бота работают без сети и токена.
Общая задача `:backend:build` собирает и проверяет оба приложения, `:backend:clean` очищает их результаты.
Отчёты находятся в `backend/api/build/reports/tests/test/` и `backend/bot/build/reports/tests/test/`.

## Отдельный запуск

В двух терминалах с нужными переменными окружения:

```powershell
.\gradlew.bat :backend:api:bootRun
```

```powershell
.\gradlew.bat :backend:bot:bootRun
```

API требует доступную MongoDB; бот — токен и список разрешённых пользователей. Настройки и
границы каждой реализации описаны по ссылкам выше. Прямой запуск через Gradle, JAR или IDEA
не читает `.env` автоматически. Для API скрипт `scripts/run-backend.ps1` явно загружает этот
файл и запускает только `:backend:api:bootRun`. См. [локальное окружение](../docs/local-environment.md):
Compose запускает MongoDB, `/api/v1/healthz` проверяет её доступность из API.
Для одного и того же Telegram-бота задавайте обоим приложениям одинаковые токен и allowlist.

После обновления структуры выполните Reload All Gradle Projects в IDEA. Для существующей
конфигурации BotApplication выберите classpath `backend.bot.main` (название может иметь префикс
проекта); локальные переменные окружения сохраните. Для HealthTgApplication используйте
`backend.api.main`. Не сохраняйте секреты в общих файлах конфигурации запуска.

Настоящую работу в Telegram и открытие Mini App проверяет человек по [чеклисту бота](bot/README.md#ручная-приёмка).