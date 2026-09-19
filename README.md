# Health-TG-Practice

Учебный проект мультимодального дневника самочувствия с Mini App.

## Стек и текущее состояние

| Область | Технологии | Состояние в этой версии репозитория |
|---|---|---|
| Java-инструменты | Java 21, Gradle 8.11.1, JUnit 5.11.4 | Подключены для проверки контрактов |
| Backend | Spring Boot, Spring Web, MongoDB, Liquibase | Выбранный стек; приложение, БД и миграции ещё не подключены |
| Frontend | React, TypeScript, Vite | Выбранный стек; приложение ещё не добавлено |

В репозитории есть OpenAPI, JSON-схемы, примеры и Java-инструмент проверки контрактов. Единственный подключённый Gradle-модуль — `contract-validator` в `tools/contract-validator/`. Каталог `backend/` пока содержит только документацию и не является Gradle-модулем. Docker Compose и CI ещё не настроены.

## Структура

| Каталог | Назначение |
|---|---|
| [backend](backend/README.md) | Место реализации backend |
| [frontend](frontend/README.md) | Место реализации Mini App |
| [contracts](contracts/README.md) | API-контракты, схемы, примеры и ограничения |
| `tools/contract-validator/` | Существующий Java-инструмент проверки контрактов |
| [fixtures](fixtures/README.md) | Место общих синтетических наборов данных |
| [tests](tests/README.md) | Место сквозных проверок продукта |
| [docs](docs/README.md) | Карта документации и описание структуры |

Gradle Wrapper и общая конфигурация сборки находятся в корне репозитория.

## Проверка контрактов

Требуется JDK 21. Установите `JAVA_HOME` на каталог JDK 21. Отдельная установка Gradle не нужна: Wrapper использует версию 8.11.1. При первом запуске требуется доступ к сети для загрузки Gradle и зависимостей из Maven Central.

Все команды выполняются из корня репозитория — каталога с `settings.gradle.kts` и `gradlew.bat`.

Windows PowerShell:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat validateContracts
.\gradlew.bat :contract-validator:validate
```

Linux/macOS:

```sh
java -version
sh ./gradlew --version
sh ./gradlew validateContracts
sh ./gradlew :contract-validator:validate
```

`validateContracts` запускает тесты валидатора. `:contract-validator:validate` запускает сам валидатор для каталога `contracts/`. Эти проверки подтверждают проверяемые свойства контрактов, но не работоспособность будущего HTTP API, базы данных или Mini App.

## Открытие в IntelliJ IDEA

1. Выберите **File → Open** и откройте корневой каталог репозитория.
2. Загрузите проект как Gradle-проект. Если IDEA не предложила импорт, откройте панель **Project** (`Alt+1`), нажмите правой кнопкой на корневой `build.gradle.kts` и выберите **Link Gradle Project**.
3. В **File → Project Structure → Project SDK** выберите JDK 21.
4. В **Settings → Build, Execution, Deployment → Build Tools → Gradle** выберите Wrapper как источник Gradle и JDK 21 в поле **Gradle JVM**.
5. Дождитесь синхронизации. В панели Gradle должен появиться модуль `contract-validator`. Проверки можно запустить командами выше во встроенном терминале из корня репозитория.

## Документация

- [Структура репозитория](docs/repository-layout.md)
- [Рабочий процесс](WORKFLOW.md)
- [Зависимости задач](DEPENDENCIES.md)
- [Проектные решения](DECISIONS.md)
- [Правила проверки результатов](VALIDATION.md)
- [Исходный документ проекта](student_project_complete_7673.pdf)

Исходный PDF содержит ранние технические решения и может отличаться от выбранного стека, указанного выше. Команды для текущего кода приведены в этом README и документации соответствующего инструмента.
