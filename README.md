# Health-TG-Practice

Учебный проект мультимодального дневника самочувствия с Mini App.

## Стек и текущее состояние

| Область | Технологии | Состояние в этой версии репозитория |
|---|---|---|
| Java-инструменты | Java 21, Gradle 8.11.1, JUnit | Общая сборка модулей backend и проверки контрактов; версии зависимостей закреплены в сборках модулей |
| Backend | Spring Boot, TelegramBots | Каркас Telegram-бота; запуск и границы описаны в backend/README.md |
| API и хранение | Spring Web, MongoDB, Liquibase | Выбранный стек; HTTP API, БД и миграции этим модулем бота не реализованы |
| Frontend | React, TypeScript, Vite | Выбранный стек; приложение ещё не добавлено |

Общая сборка включает `contract-validator` в `tools/contract-validator/` и `backend` в `backend/`. Контракты, JSON-схемы и примеры находятся в `contracts/`. Инструкции запуска бота и передачи сценария быстрых отметок — в [backend/README.md](backend/README.md). Docker Compose и CI ещё не настроены.

## Структура

| Каталог | Назначение |
|---|---|
| [backend](backend/README.md) | Java-модуль с каркасом Telegram-бота |
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

Если на Windows в пути с кириллицей Gradle сообщает `ClassNotFoundException` при запуске тестового процесса, используйте параметр совместимости кодировки:

```powershell
.\gradlew.bat '-Dorg.gradle.jvmargs=-Dfile.encoding=COMPAT' validateContracts :contract-validator:validate --no-daemon
```

Параметр действует на этот запуск Gradle; он не меняет исходники и системные настройки кодировки.

## Открытие в IntelliJ IDEA

1. Выберите **File → Open** и откройте корневой каталог репозитория.
2. Загрузите проект как Gradle-проект. Если IDEA не предложила импорт, откройте панель **Project** (`Alt+1`), нажмите правой кнопкой на корневой `build.gradle.kts` и выберите **Link Gradle Project**.
3. В **File → Project Structure → Project SDK** выберите JDK 21.
4. В **Settings → Build, Execution, Deployment → Build Tools → Gradle** выберите Wrapper как источник Gradle и JDK 21 в поле **Gradle JVM**.
5. Дождитесь синхронизации. В панели Gradle должны появиться модули `contract-validator` и `backend`. Проверки контрактов можно запустить командами выше во встроенном терминале из корня репозитория; запуск и тесты бота описаны в [backend/README.md](backend/README.md).

## Документация

- [Структура репозитория](docs/repository-layout.md)
- [Рабочий процесс](WORKFLOW.md)
- [Зависимости задач](DEPENDENCIES.md)
- [Проектные решения](DECISIONS.md)
- [Правила проверки результатов](VALIDATION.md)
- [Исходный документ проекта](student_project_complete_7673.pdf)

Исходный PDF содержит ранние технические решения и может отличаться от выбранного стека, указанного выше. Команды для текущего кода приведены в этом README и документации соответствующего инструмента.
