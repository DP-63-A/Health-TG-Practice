import {
  render,
  screen,
  within,
} from '@testing-library/react'
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import {
  readContractFixture,
} from './fixtures/fe2-06-contracts'

import type {
  ContractFixture,
} from './fixtures/fe2-06-contracts'

import { NutritionCard } from '../components/Overview-components/NutritionCard'
import { MealCountCard } from '../components/Overview-components/MealCountCard'
import { SleepCard } from '../components/Overview-components/SleepCard'
import { StepsCard } from '../components/Overview-components/StepsCard'
import { HeartRateCard } from '../components/Overview-components/HeartRateCard'
import { CheckinCard } from '../components/Overview-components/CheckinCard'

interface CardOracle {
  fixture: ContractFixture
  nutrition: readonly [string, string, string, string]
  meals: string
  mealUnit: string
  sleep: readonly [string, string, string]
  steps: readonly [string, string, string]
  period: string
  periodDays: string
  coverage: string
  pulse: string
  checkins: readonly [string, string, string, string]
}

// Ручные ожидания отображения готовых ответов BE3.
// В тестах нет расчёта сумм, средних или production-formatter.
//
// coverage — отдельное ожидание UI.
// Правило процентов и округления нужно подтвердить на review.
const cases: CardOracle[] = [
  {
    fixture: 'normal',
    nutrition: ['930 ккал', '50 г', '30 г', '110 г'],
    meals: '2',
    mealUnit: 'приёма пищи',
    sleep: ['15 ч', '7 ч 30 мин', '2'],
    steps: ['5 000', '5 000', '1'],
    period: '13.09.2026 — 19.09.2026',
    periodDays: '7',
    coverage: '14',
    pulse: '72',
    checkins: ['4 из 5', '5 из 5', '3 из 5', '4 из 5'],
  },
  {
    fixture: 'empty',
    nutrition: ['— ккал', '— г', '— г', '— г'],
    meals: '0',
    mealUnit: 'приёмов пищи',
    sleep: ['—', '—', '0'],
    steps: ['—', '—', '0'],
    period: '19.09.2026 — 19.09.2026',
    periodDays: '1',
    coverage: '0',
    pulse: '—',
    checkins: ['—', '—', '—', '—'],
  },
  {
    fixture: 'gaps',
    nutrition: ['— ккал', '— г', '— г', '— г'],
    meals: '0',
    mealUnit: 'приёмов пищи',
    sleep: ['15 ч', '7 ч 30 мин', '2'],
    steps: ['—', '—', '0'],
    period: '13.09.2026 — 19.09.2026',
    periodDays: '7',
    coverage: '0',
    pulse: '—',
    checkins: ['—', '—', '—', '—'],
  },
  {
    fixture: 'dedup',
    nutrition: ['600 ккал', '30 г', '20 г', '70 г'],
    meals: '1',
    mealUnit: 'приём пищи',
    sleep: ['—', '—', '0'],
    steps: ['5 000', '5 000', '1'],
    period: '19.09.2026 — 19.09.2026',
    periodDays: '1',
    coverage: '100',
    pulse: '—',
    checkins: ['—', '—', '—', '—'],
  },
  {
    fixture: 'filtered',
    nutrition: ['300 ккал', '15 г', '10 г', '35 г'],
    meals: '1',
    mealUnit: 'приём пищи',
    sleep: ['—', '—', '0'],
    steps: ['—', '—', '0'],
    period: '19.09.2026 — 19.09.2026',
    periodDays: '1',
    coverage: '0',
    pulse: '—',
    checkins: ['—', '—', '—', '—'],
  },
]

function normalize(value: string | null): string {
  return (value ?? '')
    .replace(/[\u00a0\u202f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function element(
  root: ParentNode,
  selector: string,
): HTMLElement {
  const found = root.querySelector<HTMLElement>(selector)

  if (!found) {
    throw new Error(`Не найден элемент: ${selector}`)
  }

  return found
}

function expectText(
  root: ParentNode,
  selector: string,
  expected: string,
) {
  const target = element(root, selector)

  expect(target).toBeVisible()
  expect(normalize(target.textContent)).toBe(expected)
}

function directText(root: Element): string {
  return normalize(
    Array.from(root.childNodes)
      .filter((node) => node.nodeType === Node.TEXT_NODE)
      .map((node) => node.textContent ?? '')
      .join(''),
  )
}

function expectStat(
  root: HTMLElement,
  label: string,
  expected: string,
) {
  const labelElement = within(root).getByText(label, {
    exact: true,
  })

  const parent = labelElement.parentElement

  if (!parent) {
    throw new Error(`Не найден контейнер "${label}"`)
  }

  expectText(parent, 'strong', expected)
}

beforeEach(() => {
  // Здесь проверяем текст карточек.
  // Настоящее отображение графика и tooltip проверим отдельно.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    },
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe.each(cases)(
  'FE2-06: числа карточек / $fixture',
  (oracle) => {
    it('питание: энергия, все нутриенты, единицы и отсутствие неполноты', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <NutritionCard nutrition={data.cards.nutrition} />,
      )

      const card = screen.getByRole('region', {
        name: 'Калории и БЖУ',
      })

      expectText(
        card,
        '.nutrition-card__energy',
        oracle.nutrition[0],
      )

      expectStat(card, 'Белки', oracle.nutrition[1])
      expectStat(card, 'Жиры', oracle.nutrition[2])
      expectStat(card, 'Углеводы', oracle.nutrition[3])

      // Все пять текущих полных ответов имеют incomplete=false.
      expect(
        within(card).queryByRole('status'),
      ).not.toBeInTheDocument()
    })

    it('приёмы пищи: количество и подпись, включая настоящий ноль', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <MealCountCard mealCount={data.cards.meal_count} />,
      )

      const card = screen.getByRole('region', {
        name: 'Количество приёмов пищи',
      })

      expectText(
        card,
        '.meal-count-card__value strong',
        oracle.meals,
      )

      expect(
        within(card).getByText(oracle.mealUnit, {
          exact: true,
        }),
      ).toBeVisible()
    })

    it('сон: общий итог, среднее, дни с данными и период', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <SleepCard
          sleep={data.cards.sleep}
          period={data.period}
        />,
      )

      const card = screen.getByRole('region', {
        name: 'Аналитика сна',
      })

      expectText(
        card,
        '.sleep-card__total',
        oracle.sleep[0],
      )

      expectStat(card, 'Среднее', oracle.sleep[1])
      expectStat(card, 'Дней с данными', oracle.sleep[2])

      expect(
        within(card).getByText('за день с данными', {
          exact: true,
        }),
      ).toBeVisible()

      expectText(
        card,
        '.sleep-card__period',
        oracle.period,
      )
    })

    it('шаги: итог, среднее, дни, единицы, покрытие и период', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <StepsCard
          steps={data.cards.steps}
          period={data.period}
        />,
      )

      const card = screen.getByRole('region', {
        name: 'Аналитика шагов',
      })

      const total = element(card, '.steps-card__total')

      expect(total).toBeVisible()
      expect(directText(total)).toBe(oracle.steps[0])

      expect(
        within(total).getByText('шагов', { exact: true }),
      ).toBeVisible()

      expectStat(card, 'Среднее', oracle.steps[1])
      expectStat(card, 'Дней с данными', oracle.steps[2])

      expect(
        within(card).getByText('шагов в день', {
          exact: true,
        }),
      ).toBeVisible()

      expect(
        within(card).getByText(`из ${oracle.periodDays}`, {
          exact: true,
        }),
      ).toBeVisible()

      expectText(
        card,
        '.steps-card__coverage-header strong',
        `${oracle.coverage}%`,
      )

      expectText(
        card,
        '.steps-card__coverage-description',
        `Данные записаны за ${oracle.steps[2]} из ${oracle.periodDays} дней`,
      )

      const progress = within(card).getByRole(
        'progressbar',
        { name: 'Покрытие периода данными о шагах' },
      )

      expect(progress).toHaveAttribute(
        'aria-valuenow',
        oracle.coverage,
      )

      expectText(
        card,
        '.steps-card__period',
        oracle.period,
      )
    })

    it('пульс: число, единица, время в timezone ответа и подпись', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <HeartRateCard
          heartRate={data.cards.heart_rate}
          period={data.period}
        />,
      )

      const card = screen.getByRole('region', {
        name: 'Аналитика пульса',
      })

      const value = element(
        card,
        '.heart-rate-card__value',
      )

      expect(value).toBeVisible()
      expect(directText(value)).toBe(oracle.pulse)

      expect(
        within(value).getByText('уд/мин', { exact: true }),
      ).toBeVisible()

      if (oracle.fixture === 'normal') {
        expectText(card, '.heart-rate-card__time', '2026-09-19 · время неизвестно')
        expect(within(card).getByText('Сообщено', { exact: true })).toBeVisible()
        const time = card.querySelectorAll('.heart-rate-card__time')[1]

        expect(time).toBeVisible()

        // Фиксированное ожидание:
        // 10:15 UTC отображается как 12:15 Europe/Warsaw.
        // Intl/production-formatter в ожидании не вызывается.
        expect(normalize(time.textContent))
          .toContain('19.09.2026')
        expect(normalize(time.textContent))
          .toContain('12:15')

        expect(
          within(card).getByText('В покое', {
            exact: true,
          }),
        ).toBeVisible()
      } else {
        expectText(
          card,
          '.heart-rate-card__time',
          'неизвестно · время неизвестно',
        )

        expect(
          card.querySelector('.heart-rate-card__qualifier'),
        ).not.toBeInTheDocument()
      }
    })

    it('состояние: значения всех четырёх категорий и шкала', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <CheckinCard checkins={data.cards.checkins} />,
      )

      const card = screen.getByRole('region', {
        name: 'Субъективные оценки состояния',
      })

      const labels = [
        'Качество сна',
        'Комфорт пищеварения',
        'Самочувствие',
        'Настроение',
      ]

      for (const [index, label] of labels.entries()) {
        expectStat(
          card,
          label,
          oracle.checkins[index],
        )
      }

      expect(
        card.querySelectorAll('.checkin-card__value'),
      ).toHaveLength(4)
    })
  },
)