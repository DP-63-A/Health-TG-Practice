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
├── backend/                  документация будущего backend
├── frontend/                 документация будущего Mini App
├── contracts/                контракты, схемы и примеры
├── tools/contract-validator/ Java-инструмент проверки контрактов
├── fixtures/                 место общих синтетических данных
├── tests/                    место сквозных проверок
└── docs/                     карта документации
```

Рабочий процесс, зависимости, решения, правила проверки и исходный PDF остаются в корне. Ссылки на них собраны в [карте документации](README.md).

## Сборка и проверки

Корневой `settings.gradle.kts` подключает единственный модуль `contract-validator`, расположенный в `tools/contract-validator/`. Корневая задача `validateContracts` вызывает его тесты. Версия Gradle задаётся существующим Wrapper: 8.11.1; Java toolchain модуля — 21.

Каталоги `backend/`, `frontend/`, `fixtures/` и `tests/` пока содержат описания назначения. Они не добавляют исполняемые приложения или новые Gradle-модули.

Тесты отдельного Java-модуля хранятся в его `src/test/java/`, тестовые ресурсы — в `src/test/resources/`. Корневой каталог `tests/` предназначен для сквозных сценариев продукта. Примеры контрактов остаются в `contracts/examples/`, общие синтетические наборы — в `fixtures/`.

Команды текущей сборки и порядок открытия в IntelliJ IDEA приведены в [корневом README](../README.md).
