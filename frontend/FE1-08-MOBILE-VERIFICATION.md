# Issue #37 / FE1-08 — Mobile verification protocol

Источник: `student_project_complete_7673.pdf`, ТЗ v1.0 от 16.09.2026:
FR-07 (стр. 8), раздел 8 (стр. 12–13), G-12 (стр. 16), FE1-08 (стр. 20).

**Issue не закрыт. REAL Telegram verification, FE2 review и human acceptance:
NOT VERIFIED / HUMAN REQUIRED.** Автоматизация и browser emulation не заменяют
проверку хотя бы одного настоящего мобильного Telegram-клиента.

## Версия и окружение под проверкой

| Поле | Значение / инструкция |
| --- | --- |
| Ветка | `fe1-08-responsive-accessibility` |
| Commit under test | Проверенный SHA после синхронизации указан в [PR #78](https://github.com/DP-63-A/Health-TG-Practice/pull/78). Для ручного прогона отдельно записать `git rev-parse HEAD` |
| База синхронизации | `origin/develop` на `c5149e3b027a65cfd728f920eef081c1985c2ad6`; изменения дня итога шагов и соответствующие тесты сохранены |
| URL / deployment / build | Заполнить при ручной проверке |
| Дата / проверяющий | Заполнить при ручной проверке |
| API mode / backend version | Явно записать `fixture` или `live`, окружение и версию backend без secrets |

Browser fixture запуск: из `frontend` выполнить `npm run dev`, открыть `/diary`.
По умолчанию используется fixture mode; полный reload восстанавливает исходные
fixture данные. Для live использовать существующую конфигурацию проекта и
разрешённый учебный аккаунт. Fixture результат не подтверждает работу live API.
Для REAL Telegram открыть существующий HTTPS Mini App из настоящего клиента;
обычный browser и Telegram Desktop не считаются мобильным Telegram-клиентом.

Выбрать реальные тестовые draft и confirmed записи. Пройти формы meal, metrics,
checkin и note; обязательные поля не скрывать. Удаление и конфликт воспроизводить
на учебных данных. Для loading/pending/422/409/error использовать согласованный
тестовый стенд или явно записанное browser-only API interception. Если состояние
не воспроизводится безопасно, оставить его NOT VERIFIED и записать причину.
Не добавлять production fault switches и не подменять live режим fixtures.

## REAL Telegram mobile client — NOT VERIFIED / HUMAN REQUIRED

| Поле | Фактическое значение |
| --- | --- |
| OS / версия / device | NOT VERIFIED |
| Telegram client / версия | NOT VERIFIED |
| Screen size / CSS viewport width × height / scale | NOT VERIFIED |
| Light/dark theme / fullscreen или обычный Mini App | NOT VERIFIED |
| URL / API mode / backend environment / версия | NOT VERIFIED |
| Commit SHA / дата / проверяющий | NOT VERIFIED |
| Actual result | NOT VERIFIED — запуск на реальном устройстве не выполнялся |
| Итог PASS / FAIL / NOT VERIFIED | NOT VERIFIED |
| Evidence: screenshot / video / manual notes | Не предоставлено |

Выполнить checklist ниже с открытой и закрытой software keyboard, в обеих темах.
Записать реальные размеры viewport. Хотя бы одна из проверок 360/390 должна
иметь evidence настоящего мобильного Telegram-клиента; второй размер допустимо
проверить эмулятором. Если реальные размеры другие, записать ограничение и
оставить требуемую размерную проверку незавершённой.

## Emulator — второй размер 360 или 390

| Поле | Фактическое значение ручного прогона |
| --- | --- |
| Browser / версия / OS / emulator preset | NOT VERIFIED |
| Width × height / scale | NOT VERIFIED — выбрать 360 или 390, второй размер относительно REAL client |
| Theme / simulated insets / keyboard simulation | NOT VERIFIED |
| URL / fixture или live / backend / commit SHA | NOT VERIFIED |
| Actual result / PASS или FAIL | NOT VERIFIED |
| Evidence: screenshot / video / notes | Не предоставлено |

В DevTools задать выбранную ширину в CSS px и проверить
`document.documentElement.clientWidth`. Повторить checklist для light/dark.
Уменьшение высоты viewport — только keyboard simulation, не доказательство
поведения настоящей Telegram software keyboard.

## Checklist — повторить для каждого окружения и темы

Для каждой строки записать REAL и emulator результат отдельно: actual result,
PASS/FAIL/NOT VERIFIED, evidence и найденный дефект. Не отмечать PASS по наличию
CSS или по результату jsdom tests.

| Сценарий / точные шаги | Expected result | REAL | Emulator |
| --- | --- | --- | --- |
| Открыть `/diary`; прокрутить карточки с длинными описаниями, единицами и source labels | Текст и значения читаемы; ничего не перекрывается и не скрывается; нет горизонтального скролла | NOT VERIFIED | NOT VERIFIED |
| Tab или touch пройти «Режим», «С даты», «По дату», «Тип»; изменить каждый фильтр | Поля имеют labels; выбранные значения читаемы; виден актуальный результат | NOT VERIFIED | NOT VERIFIED |
| При наличии нескольких страниц нажать «Вперёд» и «Назад», затем изменить фильтр | Actions достижимы, номер страницы виден; disabled actions недоступны; фильтр возвращает на первую страницу | NOT VERIFIED | NOT VERIFIED |
| Проверить loading, пустой результат, ошибку загрузки и «Повторить» | Понятные отдельные текстовые состояния; retry читаем и доступен; нет ложного empty/success | NOT VERIFIED | NOT VERIFIED |
| Открыть detail с source/file; проверить длинную подпись, дату, revision, происхождение, file loading/error и file link | Обязательные значения не перекрываются; источник открывается существующим способом; ошибка объяснена | NOT VERIFIED | NOT VERIFIED |
| Открыть draft каждого типа; отредактировать поля; пройти «Изменить», save и confirm | Все поля доступны и подписаны; «Изменить» фокусирует дату, для steps — день итога шагов; actions читаемы; existing save/confirm behavior сохранён | NOT VERIFIED | NOT VERIFIED |
| Открыть confirmed каждого типа; изменить поле и сохранить | Поля и save доступны; введённое значение сохраняется только после успешного ответа | NOT VERIFIED | NOT VERIFIED |
| Ввести отрицательную массу, пустое обязательное описание/текст или score вне 1–5; сохранить | Видна текстовая ошибка; соответствующее поле имеет `aria-invalid`, связано с error и получает focus; API mutation не отправляется | NOT VERIFIED | NOT VERIFIED |
| Получить согласованный 422 field error; исправить поле | Error программно связан с правильным полем; ввод сохранён; после исправления нет dangling error description | NOT VERIFIED | NOT VERIFIED |
| Безопасно вызвать 409; сравнить свежую версию и свой ввод; открыть подтверждение замены | Ввод не заменён автоматически; длинный JSON переносится и прокручивается с клавиатуры; confirmations достижимы | NOT VERIFIED | NOT VERIFIED |
| Задержать mutation response; проверить pending и затем восстановление controls | Pending объяснён текстом; disabled controls пропускаются Tab; нет focus trap или повторной mutation | NOT VERIFIED | NOT VERIFIED |
| На confirmed нажать «Убрать из дневника», затем «Оставить запись»; при разрешении отдельно подтвердить учебное удаление | Confirmation и cancel читаемы/достижимы; существующая destructive semantics сохранена | NOT VERIFIED | NOT VERIFIED |
| Ввести несохранённый текст; открыть keyboard; scroll к нижним полям и обязательным actions; закрыть keyboard; изменить ориентацию/viewport | Поле и actions достижимы; ввод не теряется; поля не remount; page остаётся scrollable | NOT VERIFIED | NOT VERIFIED |
| Проверить верх/низ/боковые края в обычном и поддерживаемом fullscreen режиме, с keyboard и без неё | Контент и actions не под системным или Telegram UI; safe areas не создают horizontal overflow | NOT VERIFIED | NOT VERIFIED |
| Переключить light/dark в Telegram, в том числе при иной OS theme; повторить error/conflict/pending | Labels, текст, buttons и focus читаемы; смысл состояний не зависит только от цвета | NOT VERIFIED | NOT VERIFIED |
| С доступной физической клавиатурой пройти Tab/Shift+Tab по filters, forms, confirmations и source link | Порядок соответствует DOM/экрану; focus видим; native date/time segments работают; disabled controls пропускаются | NOT VERIFIED | NOT VERIFIED |
| Вернуться из чата с несохранённым вводом; проверить refresh и ручное «Обновить» | Ввод не теряется; существующие lifecycle/conflict правила сохранены | NOT VERIFIED | NOT VERIFIED |

Overflow check: горизонтальный swipe не двигает document/page. При доступных
DevTools сравнить `document.documentElement.scrollWidth` и `clientWidth`:
scrollWidth не больше clientWidth (допуск 1 CSS px на округление). Дополнительно
проверить визуально labels, длинные значения и actions: отсутствие page overflow
само по себе не доказывает отсутствие локального обрезания или перекрытия.

## Browser evidence audit — ad-hoc, не acceptance evidence

Предыдущие заявления «184 Chromium layout checks / 14 native keyboard flows»
относятся к временному скрипту `%TEMP%/fe1-08-audit-tools/browser-audit.mjs`
и его `browser-results.json`, а не к repository test suite. Скрипт использовал
Node.js fetch/WebSocket и Chrome DevTools Protocol headless Edge. Историческая
команда PowerShell: `node "$env:TEMP/fe1-08-audit-tools/browser-audit.mjs"`.
Для неё отдельно запускались Vite на `127.0.0.1:5178` и браузер с remote debugging
на порту `9338`; скрипт зависит от этих адресов и временных файлов.

Скрипт, результаты, screenshots и browser test config отсутствуют в HEAD и
текущем diff. В package scripts/CI нет команды этих проверок. После fresh checkout
они невоспроизводимы средствами репозитория. Эти числа и временные артефакты
**не учитываются как automated evidence AC1/AC2/AC3**. Visual verification 360/390,
Tab/Shift+Tab, видимость focus и реальное поведение keyboard требуют ручного
прогона checklist с evidence и commit SHA. REAL и emulator results остаются
NOT VERIFIED. Симуляция CSS insets/theme или уменьшение высоты viewport не
подтверждают поведение настоящего Telegram-клиента.

## Воспроизводимые repository regression gates

Vitest покрывает native labels/names, уникальные IDs, отсутствие dangling
descriptions, local/422 error associations и focus, очистку ошибки, сохранение
draft/confirmed input и focus при window/visualViewport resize. Jsdom не
доказывает реальный visual layout, native Tab navigation или software keyboard.

Команды из `frontend` для повторения regression gates:

```sh
npm test -- src/components/ui/FormField.test.tsx src/pages/DiaryPage.test.tsx src/pages/EntryPage.test.tsx
npm test
npm run lint
npm run typecheck
npm run build
```

Из корня: `git diff --check`, `git status --short`, `git diff --stat`,
`git diff --name-only`. Перед commit проверить, что FormField test и этот документ включены в diff.
Build output и временные browser artifacts не добавлять в Git.

Исторические проверки рабочего дерева (01.10.2026): targeted suite —
3 файла / 88 тестов PASS; полный suite — 12 файлов / 148 тестов PASS;
lint, typecheck и build PASS. `git diff --check` PASS, только предупреждения
о нормализации CRLF → LF. Свежий built HTML сохранил SDK и `viewport-fit=cover`;
generated artifacts отсутствуют в `git status`. Это локальные результаты,
не отчёт CI и не подтверждение будущего commit SHA.

Проверки после синхронизации с `origin/develop` (06.10.2026): `npm ci --no-audit --no-fund`
PASS; полный `npm test` — 12 файлов / 149 тестов PASS; `npm run lint`,
`npm run typecheck`, `npm run build` и `git diff --check` PASS. Итоговый diff
относительно `origin/develop` содержит только 8 файлов FE1-08. В `EntryPage.test.tsx`
сохранены все актуальные тесты develop и проверки FE1-08, assertions не ослаблены.
Built HTML сохраняет Telegram SDK и `viewport-fit=cover`. Проверенный SHA указан
в PR #78; результаты локальные, REAL Telegram и ручной emulator checklist остаются
NOT VERIFIED.

## Acceptance / review gates

| AC | Automated evidence | Manual evidence | Статус |
| --- | --- | --- | --- |
| AC1 | Jsdom не проверяет layout/overflow; CSS проверен статически | Visual verification 360/390: NOT VERIFIED; REAL + emulator checklist не заполнены | NOT VERIFIED |
| AC2 | Vitest labels/names, unique IDs, error associations, focus и disabled behavior | Native Tab/Shift+Tab и видимость focus в темах: NOT VERIFIED | PARTIAL |
| AC3 | Vitest сохраняет input/focus при resize; CSS fallbacks проверены статически | Telegram safe areas/themes/software keyboard: NOT VERIFIED | PARTIAL |
| AC4 | Автоматизация не закрывает этот критерий | REAL Telegram mobile client: NOT VERIFIED / HUMAN REQUIRED | NOT VERIFIED |
| AC5 | Локальные tests/lint/typecheck/build PASS после синхронизации с develop; проверенный SHA указан в PR #78 | FE2 review и human acceptance отсутствуют | PARTIAL |

FE2 review: **NOT VERIFIED**. Reviewer / дата / SHA / замечания / решение:
заполнить другим участником. Проверить общие tokens, button/state contrast,
card padding, safe-area/layout, focus и FormField API (один непосредственный
native control, optional `error`). Overview/charts logic не менялась; её visual
review остаётся за FE2.

Human acceptance: **NOT VERIFIED**. Проверяющий / дата / SHA / evidence / решение:
заполнить человеком после REAL client и второй ширины. При FAIL сохранить шаги
воспроизведения и повторить затронутые проверки после исправления.
