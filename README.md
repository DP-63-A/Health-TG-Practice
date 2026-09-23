# Health-TG-Practice

Учебный проект мультимодального дневника самочувствия с Telegram Mini App.

## Стек и текущее состояние

| Область | Технологии | Текущее состояние |
|---|---|---|
| Общая сборка | Java 21, Gradle 8.11.1 | Подключены модули `backend` и `contract-validator` |
| Backend | Spring Boot 3.5.6, Spring Web, Spring Security, Spring Data MongoDB | Реализованы Telegram-аутентификация, сессии, `/api/v1/me` и owner guard |
| Миграции | Liquibase | Входит в согласованный стек, пока не подключён |
| Frontend | React, TypeScript, Vite | Выбранный стек; приложение ещё не добавлено |
| Окружение | Docker Compose, GitHub Actions | Полная конфигурация пока не добавлена |

В репозитории также находятся OpenAPI, JSON-схемы, примеры и Java-инструмент проверки контрактов.
Наличие контрактов и тестовых данных само по себе не подтверждает работу полного HTTP API.

## Структура

| Каталог | Назначение |
|---|---|
| [backend](backend/README.md) | Spring Boot backend, конфигурация и модульные тесты |
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
.\gradlew.bat :backend:test :backend:bootJar validateContracts :contract-validator:validate --console=plain
```

Linux/macOS:

```sh
java -version
sh ./gradlew --version
sh ./gradlew :backend:test :backend:bootJar validateContracts :contract-validator:validate --console=plain
```

`:backend:test` запускает backend-тесты, `:backend:bootJar` собирает запускаемый JAR,
`validateContracts` запускает тесты валидатора, а `:contract-validator:validate` проверяет OpenAPI,
JSON-схемы и примеры. MongoDB-интеграционные тесты требуют Docker.

## Открытие в IntelliJ IDEA

1. Выберите **File -> Open** и откройте корневой каталог репозитория.
2. Загрузите проект как Gradle-проект. Если IDEA не предложила импорт, свяжите корневой
   `build.gradle.kts` через **Link Gradle Project**.
3. В **File -> Project Structure -> Project SDK** выберите JDK 21.
4. В **Settings -> Build, Execution, Deployment -> Build Tools -> Gradle** выберите Wrapper и JDK 21.
5. После синхронизации в панели Gradle должны появиться модули `backend` и `contract-validator`.

## Документация

- [Структура репозитория](docs/repository-layout.md)
- [Рабочий процесс](WORKFLOW.md)
- [Зависимости задач](DEPENDENCIES.md)
- [Проектные решения](DECISIONS.md)
- [Правила проверки результатов](VALIDATION.md)
- [Исходный документ проекта](student_project_complete_7673.pdf)

Исходный PDF содержит ранние технические решения и может отличаться от текущего состояния.
Актуальные команды находятся в этом README и документации соответствующих модулей.
