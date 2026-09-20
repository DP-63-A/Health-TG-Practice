# Структура репозитория

## Текущая организация

```text
Health-TG-Practice/
├── README.md
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
├── backend/                  Java-модуль: каркас Telegram-бота, тесты, инструкция запуска
├── frontend/                 документация будущего Mini App
├── contracts/                контракты, схемы и примеры
├── tools/contract-validator/ Java-инструмент проверки контрактов
├── fixtures/                 место общих синтетических данных
├── tests/                    место сквозных проверок
└── docs/                     карта документации
```

Рабочий процесс, зависимости, решения, правила проверки и исходный PDF остаются в корне. Ссылки на них собраны в [карте документации](README.md).

## Сборка и проверки

Корневой `settings.gradle.kts` подключает `contract-validator` в `tools/contract-validator/` и `backend` в `backend/`. Корневая задача `validateContracts` вызывает тесты валидатора; `:backend:test` — тесты бота. Версия Gradle задаётся общим Wrapper: 8.11.1; Java toolchain обоих модулей — 21.

В `backend/src/main/java/` расположены логика команд и Telegram-подключение. Настройки запуска и границы реализации описаны в [README модуля](../backend/README.md). Каталоги `frontend/`, `fixtures/` и `tests/` пока содержат описания назначения.

Тесты отдельного Java-модуля хранятся в его `src/test/java/`, тестовые ресурсы — в `src/test/resources/`. Корневой каталог `tests/` предназначен для сквозных сценариев продукта. Примеры контрактов остаются в `contracts/examples/`, общие синтетические наборы — в `fixtures/`.

Команды текущей сборки и порядок открытия в IntelliJ IDEA приведены в [корневом README](../README.md).
