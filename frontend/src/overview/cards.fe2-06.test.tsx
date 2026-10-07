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

// Ручные ожидания отображения gотовых ответов BE3.
// В тестах нет расчёта сумм, средних или production-formatter.
//
// coverage — отдельное ожидание UI.
// Правило процентов и округления нужно подтвердить на review.
const cases: CardOracle[] = [
  {
    fixture: 'normal',
    nutrition: ['930 kcal', '50 g', '30 g', '110 g'],
    meals: '2',
    mealUnit: 'meals',
    sleep: ['15 h', '7 h 30 min', '2'],
    steps: ['5,000', '5,000', '1'],
    period: '13.09.2026 — 19.09.2026',
    periodDays: '7',
    coverage: '14',
    pulse: '72',
    checkins: ['4 out of 5', '5 out of 5', '3 out of 5', '4 out of 5'],
  },
  {
    fixture: 'empty',
    nutrition: ['— kcal', '— g', '— g', '— g'],
    meals: '0',
    mealUnit: 'meals',
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
    nutrition: ['— kcal', '— g', '— g', '— g'],
    meals: '0',
    mealUnit: 'meals',
    sleep: ['15 h', '7 h 30 min', '2'],
    steps: ['—', '—', '0'],
    period: '13.09.2026 — 19.09.2026',
    periodDays: '7',
    coverage: '0',
    pulse: '—',
    checkins: ['—', '—', '—', '—'],
  },
  {
    fixture: 'dedup',
    nutrition: ['600 kcal', '30 g', '20 g', '70 g'],
    meals: '1',
    mealUnit: 'meal',
    sleep: ['—', '—', '0'],
    steps: ['5,000', '5,000', '1'],
    period: '19.09.2026 — 19.09.2026',
    periodDays: '1',
    coverage: '100',
    pulse: '—',
    checkins: ['—', '—', '—', '—'],
  },
  {
    fixture: 'filtered',
    nutrition: ['300 kcal', '15 g', '10 g', '35 g'],
    meals: '1',
    mealUnit: 'meal',
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
  // Настоящее отображение gрафика и tooltip проверим отдельно.
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
        name: 'Calories & macros',
      })

      expectText(
        card,
        '.nutrition-card__energy',
        oracle.nutrition[0],
      )

      expectStat(card, 'Protein', oracle.nutrition[1])
      expectStat(card, 'Fat', oracle.nutrition[2])
      expectStat(card, 'Carbs', oracle.nutrition[3])

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
        name: 'Meal count',
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

    it('sleep: общий итог, среднее, дни с данными и период', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <SleepCard
          sleep={data.cards.sleep}
          period={data.period}
        />,
      )

      const card = screen.getByRole('region', {
        name: 'Sleep analytics',
      })

      expectText(
        card,
        '.sleep-card__total',
        oracle.sleep[0],
      )

      expectStat(card, 'Average', oracle.sleep[1])
      expectStat(card, 'Days recorded', oracle.sleep[2])

      expect(
        within(card).getByText('per recorded day', {
          exact: true,
        }),
      ).toBeVisible()

      expectText(
        card,
        '.sleep-card__period',
        oracle.period,
      )
    })

    it('steps: итог, среднее, дни, единицы, покрытие и период', () => {
      const data = readContractFixture(oracle.fixture)

      render(
        <StepsCard
          steps={data.cards.steps}
          period={data.period}
        />,
      )

      const card = screen.getByRole('region', {
        name: 'Step analytics',
      })

      const total = element(card, '.steps-card__total')

      expect(total).toBeVisible()
      expect(directText(total)).toBe(oracle.steps[0])

      expect(
        within(total).getByText('steps', { exact: true }),
      ).toBeVisible()

      expectStat(card, 'Average', oracle.steps[1])
      expectStat(card, 'Days recorded', oracle.steps[2])

      expect(
        within(card).getByText('steps per day', {
          exact: true,
        }),
      ).toBeVisible()

      expect(
        within(card).getByText(`of ${oracle.periodDays}`, {
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
        `Data recorded for ${oracle.steps[2]} of ${oracle.periodDays} days`,
      )

      const progress = within(card).getByRole(
        'progressbar',
        { name: 'Step data coverage' },
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
        name: 'Heart rate analytics',
      })

      const value = element(
        card,
        '.heart-rate-card__value',
      )

      expect(value).toBeVisible()
      expect(directText(value)).toBe(oracle.pulse)

      expect(
        within(value).getByText('bpm', { exact: true }),
      ).toBeVisible()

      if (oracle.fixture === 'normal') {
        expectText(card, '.heart-rate-card__time', '2026-09-19 · time unknown')
        expect(within(card).getByText('Reported at', { exact: true })).toBeVisible()
        const time = card.querySelectorAll('.heart-rate-card__time')[1]

        expect(time).toBeVisible()

        // Фиксированное ожидание:
        // 10:15 UTC отображается как 12:15 Europe/Warsaw.
        // Intl/production-formatter в ожидании не вызывается.
        expect(normalize(time.textContent))
          .toContain('19/09/2026')
        expect(normalize(time.textContent))
          .toContain('12:15')

        expect(
          within(card).getByText('At rest', {
            exact: true,
          }),
        ).toBeVisible()
      } else {
        expectText(
          card,
          '.heart-rate-card__time',
          'unknown · time unknown',
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
        name: 'Subjective wellbeing scores',
      })

      const labels = [
        'Sleep quality',
        'Digestive comfort',
        'Wellbeing',
        'Mood',
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