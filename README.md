# Health-TG-Practice

Учебный проект мультимодального дневника самочувствия с Telegram Mini App.

## Стек и текущее состояние

| Область | Технологии | Текущее состояние |
|---|---|---|
| Общая сборка | Java 21, Gradle 8.11.1 | Подключены модули `backend:api`, `backend:bot` и `contract-validator` |
| API | Spring Boot 3.5.6, Spring Web, Spring Security, Spring Data MongoDB | Реализованы Telegram-аутентификация, сессии, `/api/v1/me` и owner guard |
| Бот | Spring Boot 3.5.6, TelegramBots 9.2.0 | Отдельный запуск, long polling, закрытый доступ, черновики и быстрые отметки через core storage |
| Миграции | Liquibase | Входит в согласованный стек, пока не подключён |
| Frontend | React, TypeScript, Vite | Реализованы страницы дневника, записи и обзора; API-клиент поддерживает fixture и live, сборка через npm |
| Окружение | Docker Compose, GitHub Actions | Compose собирает MongoDB, API, bot и frontend; CI проверяет Java, frontend, контракты и контейнерные сборки |

В репозитории также находятся OpenAPI, JSON-схемы, примеры и Java-инструмент проверки контрактов.
Наличие контрактов и тестовых данных само по себе не подтверждает работу полного HTTP API.
Frontend в режиме `fixture` использует локальные ответы без backend; режим `live` обращается к настроенному API. Наличие этих режимов не подтверждает полную интеграцию всех сценариев. Запуск и настройки — в [README frontend](frontend/README.md).

## Запуск согласованного окружения

Требуются Docker Desktop с Compose v2 и тестовый Telegram-бот. Скопируйте безопасный
шаблон, затем замените заглушки реальными значениями только в игнорируемом `.env`:

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:8088/api/v1/healthz
```

Mini App доступен на `http://localhost:8088`. MongoDB, API и файловый volume не
публикуются на хост: API доступен через frontend reverse proxy. Полные инструкции,
обычный перезапуск, остановка и ограничения описаны в
[локальном окружении](docs/local-environment.md).

## Структура

| Каталог | Назначение |
|---|---|
| [backend](backend/README.md) | Отдельные приложения API и бота, конфигурация и тесты |
| [frontend](frontend/README.md) | Telegram Mini App |
| [contracts](contracts/README.md) | API-контракты, схемы, примеры и ограничения |
| `tools/contract-validator/` | Java-инструмент проверки контрактов |
| [fixtures](fixtures/README.md) | Общие синтетические наборы данных |
| [tests](tests/README.md) | Сквозные проверки продукта |
| [docs](docs/README.md) | Проектная и техническая документация |

Gradle Wrapper и общая конфигурация сборки находятся в корне репозитория.

## Сборка и проверки

Требуется JDK 21. Установите `JAVA_HOME` на каталог JDK 21. Отдельная установка Gradle не нужна:
Wrapper использует Gradle 8.11.1. При первом запуске требуется доступ к сети для загрузки Gradle
и зависимостей из Maven Central.

Все команды выполняются из корня репозитория, содержащего `settings.gradle.kts` и `gradlew.bat`.

Windows PowerShell:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat :backend:api:test :backend:bot:test :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --console=plain
```

Linux/macOS:

```sh
java -version
sh ./gradlew --version
sh ./gradlew :backend:api:test :backend:bot:test :backend:api:bootJar :backend:bot:bootJar validateContracts :contract-validator:validate --console=plain
```

Задачи `test` проверяют API и бота, задачи `bootJar` собирают два отдельных запускаемых JAR,
`validateContracts` запускает тесты валидатора, а `:contract-validator:validate` проверяет OpenAPI,
JSON-схемы и примеры. MongoDB-интеграционные тесты требуют Docker.

## Открытие в IntelliJ IDEA

1. Выберите **File -> Open** и откройте корневой каталог репозитория.
2. Загрузите проект как Gradle-проект. Если IDEA не предложила импорт, свяжите корневой
   `build.gradle.kts` через **Link Gradle Project**.
3. В **File -> Project Structure -> Project SDK** выберите JDK 21.
4. В **Settings -> Build, Execution, Deployment -> Build Tools -> Gradle** выберите Wrapper и JDK 21.
5. После синхронизации в панели Gradle должны появиться модули `backend:api`, `backend:bot` и `contract-validator`.

## Документация

- [Структура репозитория](docs/repository-layout.md)
- [Локальная MongoDB и запуск API](docs/local-environment.md)
- [Рабочий процесс](WORKFLOW.md)
- [Зависимости задач](DEPENDENCIES.md)
- [Проектные решения](DECISIONS.md)
- [Правила проверки результатов](VALIDATION.md)
- [Исходный документ проекта](student_project_complete_7673.pdf)

Исходный PDF содержит ранние технические решения и может отличаться от текущего состояния.
Актуальные команды находятся в этом README и документации соответствующих модулей.
