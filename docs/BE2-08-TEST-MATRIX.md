# BE2-08 — матрица автоматических проверок бота

Issue: [#33](https://github.com/DP-63-A/Health-TG-Practice/issues/33), критерий AC1. Пути — от `backend/bot/src/test/java/org/healthtg/bot/`.

Документ сопоставляет обязательные автоматические сценарии из пункта 1 Issue #33 с тестами. Он не заменяет ручные, визуальные и live-проверки: они ведутся в [#128](https://github.com/DP-63-A/Health-TG-Practice/issues/128).

## Как запустить

Тесты с MongoDB используют временные Testcontainers, поэтому нужен запущенный Docker. Внешняя модель не вызывается.

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :backend:bot:test --no-daemon
```

CI выполняет эти тесты в задаче `backend` workflow `.github/workflows/ci.yml` вместе с `be1Acceptance`.

## Сценарии Issue #33 → тесты

| Сценарий | Тесты | Что проверяется |
|---|---|---|
| Текстовый draft | `CoreBotFlowTest.parsedTextCreatesDraftAndPersistsReviewStep`; `TextDialogStorageTest.noteRequiresConsentAndExplicitDateTimeAndKeepsOriginalTextAcrossRestart` | Текст создаёт черновик, шаг проверки сохраняется; заметка только с согласия и явными датой/временем, переживает перезапуск |
| Confirm / cancel | `DraftReviewStorageTest.confirmationRecoversBeforeAndAfterLostResponse`, `cancelledButtonsCannotAffectNextDraft` | Подтверждение восстанавливается при потере ответа до и после записи; кнопки отменённого черновика не влияют на следующий |
| Изменение даты и числа | `DraftReviewStorageTest.mealAndNoteDateKeepLocalTimeAcrossOffsetChange`, `metricDateCorrectionPreservesKnownTimeAndReportMoment`, `decimalPendingSurvivesRestartBeforeOrAfterPatch`; `correction/CorrectionInputParserTest` | Локальное время сохраняется при смене смещения; известное время и момент сообщения не теряются; дробное значение переживает перезапуск; строгий разбор ввода |
| Повтор update / callback | `FoodPhotoStorageTest.inputReplayAndNewPhotoStateAndStaleCallbacksDoNotDestroyCandidate`; `QuickCheckinAcceptanceTest.restartReplayUsesSavedTimeAndLatestEditedPayloadButStaleCancelCannotDelete`; `DraftReviewStorageTest.concurrentConfirmationCreatesOnlyOneRevision` | Повтор не создаёт дубль; устаревшая кнопка не удаляет и не перезаписывает новую версию |
| Четыре быстрые категории | `QuickCheckinAcceptanceTest.allCategoriesAndScoresHaveExactPersistedReceiptAndNoParser` | Все категории × оценки сохраняются с точной квитанцией, без вызова парсера/модели |
| Timeout | `recognition/GeminiRecognitionProviderTest.timeoutIncludesStalledBodyAndDoesNotRetry` | Общий предел включает зависшее тело ответа; автоповтора нет |
| Невалидный JSON | `recognition/RecognitionResponseParserTest` | Пустой, обрезанный, дублирующийся и лишний JSON отклоняются как ошибка, а не как данные |
| Неподдерживаемый файл | `FoodPhotoBoundaryTest.albumsWrongMimeAndDeclaredOversizeNeverLoadOrRecognize`; `recognition/ImageValidatorTest` | Альбом, неверный MIME и превышение размера не загружаются и не распознаются; границы 5 МиБ и 12 Мп |
| Восстановление draft | `FoodPhotoStorageTest.lostCreateResponseIsRecoverableWithoutDuplicateEntry`; `DraftReviewStorageTest` (сценарии перезапуска) | Потеря ответа при создании не создаёт второй записи; состояние восстанавливается после перезапуска |
| Ошибка модели → действия | `FoodPhotoStorageTest.modelErrorHasExplicitManualRetryCancelAndNoAutomaticFallback`; `HealthWatchStorageTest` | Retry / Manual / Cancel без автоматического fallback и без confirmed из ошибки |

## Fixture и отсутствие скрытого live

| Требование | Тест | Что проверяется |
|---|---|---|
| Fixture работает без внешнего API | `recognition/FoodRecognitionServiceTest.fixtureWorksWithoutKeyAndLogsOnlyMetadata` | Ключ не нужен; в лог попадают только метаданные |
| Сбой live не подменяется fixture | `recognition/FoodRecognitionServiceTest.liveFailureNeverReturnsFixtureOrRetriesAndLogsCategory` | Ошибка live остаётся ошибкой, без повтора и без заранее записанного ответа |
| Запрос к модели без лишних полномочий | `recognition/GeminiRecognitionProviderTest.requestContainsImageSchemaAndInjectionInstructionsButNoToolsFixtureOrSecretInBody` | В запросе нет инструментов, fixture и секрета |

Режим задаётся явно переменной `FOOD_RECOGNITION_MODE` (`disabled` / `fixture` / `live`), см. [FOOD-RECOGNITION.md](../backend/bot/FOOD-RECOGNITION.md).

## Результат локального запуска

Код бота и ядра совпадает с head PR #122 (`527c7c3`) и с develop `287958a`. Полный локальный запуск на этом коде (07.10.2026): `backend/bot` 910 тестов, `backend/api` 130, `backend/core` 15, `analytics` 36, `contract-validator` 11; failures / errors / skipped = 0. Отдельно `be1Acceptance`: 87 выполнений, частично повторяющих обычные тесты, без ошибок и пропусков.

В CI эти проверки выполняются в workflow `ci.yml`. JUnit XML из CI как артефакты не сохраняются, поэтому точное число тестов на GitHub по логу задачи не фиксируется.

## Что не покрывается этим документом

- Качество распознавания: fixture возвращает один и тот же ответ на любое изображение.
- 12 визуальных кейсов, три live-прогона, сквозные сценарии в Telegram и Mini App — [#128](https://github.com/DP-63-A/Health-TG-Practice/issues/128).
- Изменения обработки отказов из [#27](https://github.com/DP-63-A/Health-TG-Practice/issues/27) после его merge: матрицу нужно дополнить новыми тестами.
