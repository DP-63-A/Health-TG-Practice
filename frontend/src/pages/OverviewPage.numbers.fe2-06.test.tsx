import '@testing-library/jest-dom/vitest'

import {
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import { apiClient } from '../api/client'
import {
  readContractFixture,
} from '../overview/fixtures/fe2-06-contracts'
import type {
  ContractFixture,
} from '../overview/fixtures/fe2-06-contracts'
import { RefreshProvider } from '../refresh/RefreshProvider'
import OverviewPage from '../pages/OverviewPage'

const auth = vi.hoisted(() => ({
  markSessionExpired: vi.fn(),
  retry: vi.fn(),
  state: {
    status: 'authenticated' as const,
    user: {
      id: '11111111-1111-4111-8111-111111111101',
      telegram_id: 10001, // Синтетический тестовый ID.
      timezone: 'Europe/Warsaw',
      stand_access: true,
    },
  },
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => auth,
}))

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

function expectStat(
  root: HTMLElement,
  label: string,
  expected: string,
) {
  const parent = within(root)
    .getByText(label, { exact: true })
    .parentElement

  if (!parent) {
    throw new Error(`Не найден контейнер "${label}"`)
  }

  expectText(parent, 'strong', expected)
}

function directText(root: Element): string {
  return normalize(
    Array.from(root.childNodes)
      .filter((node) => node.nodeType === Node.TEXT_NODE)
      .map((node) => node.textContent ?? '')
      .join(''),
  )
}

function region(name: string) {
  return screen.getByRole('region', { name })
}

async function renderResponse(fixture: ContractFixture) {
  const data = readContractFixture(fixture)

  const get = vi.spyOn(apiClient, 'get')
    .mockRejectedValue(new Error('Unexpected API request'))
    .mockResolvedValueOnce(data)

  render(
    <MemoryRouter
      initialEntries={[
        `/overview?period=${data.period.kind}&checkin_category=mood`,
      ]}
    >
      <RefreshProvider>
        <OverviewPage />
      </RefreshProvider>
    </MemoryRouter>,
  )

  await waitFor(() => {
    expect(
      screen.queryByRole('heading', {
        name: 'Загрузка аналитики',
      }),
    ).not.toBeInTheDocument()
  })

  expect(get).toHaveBeenCalledExactlyOnceWith(
    '/api/v1/analytics',
    {
      query: {
        period: data.period.kind,
        timezone: 'Europe/Warsaw',
        checkin_category: 'mood',
      },
    },
  )

  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
}

beforeEach(() => {
  auth.markSessionExpired.mockReset()

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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const cases = [
  {
    fixture: 'normal',
    nutrition: ['930 ккал', '50 г', '30 г', '110 г'],
    meals: '2',
    sleep: ['15 ч', '7 ч 30 мин', '2'],
    steps: ['5 000', '5 000', '1'],
    coverage: '14',
    period: '13.09.2026 — 19.09.2026',
    pulse: '72',
    scores: ['4 из 5', '5 из 5', '3 из 5', '4 из 5'],
  },
  {
    fixture: 'gaps',
    nutrition: ['— ккал', '— г', '— г', '— г'],
    meals: '0',
    sleep: ['15 ч', '7 ч 30 мин', '2'],
    steps: ['—', '—', '0'],
    coverage: '0',
    period: '13.09.2026 — 19.09.2026',
    pulse: '—',
    scores: ['—', '—', '—', '—'],
  },
  {
    fixture: 'dedup',
    nutrition: ['600 ккал', '30 г', '20 г', '70 г'],
    meals: '1',
    sleep: ['—', '—', '0'],
    steps: ['5 000', '5 000', '1'],
    coverage: '100',
    period: '19.09.2026 — 19.09.2026',
    pulse: '—',
    scores: ['—', '—', '—', '—'],
  },
  {
    fixture: 'filtered',
    nutrition: ['300 ккал', '15 г', '10 г', '35 г'],
    meals: '1',
    sleep: ['—', '—', '0'],
    steps: ['—', '—', '0'],
    coverage: '0',
    period: '19.09.2026 — 19.09.2026',
    pulse: '—',
    scores: ['—', '—', '—', '—'],
  },
] as const

describe('FE2-06: контрактные числа через OverviewPage', () => {
  it.each(cases)(
    '$fixture: страница передаёт значения всем шести карточкам',
    async (oracle) => {
      await renderResponse(oracle.fixture)

      const nutrition = region('Калории и БЖУ')

      expectText(
        nutrition,
        '.nutrition-card__energy',
        oracle.nutrition[0],
      )

      expectStat(nutrition, 'Белки', oracle.nutrition[1])
      expectStat(nutrition, 'Жиры', oracle.nutrition[2])
      expectStat(nutrition, 'Углеводы', oracle.nutrition[3])

      expect(
        within(nutrition).queryByRole('status'),
      ).not.toBeInTheDocument()

      expectText(
        region('Количество приёмов пищи'),
        '.meal-count-card__value strong',
        oracle.meals,
      )

      const sleep = region('Аналитика сна')

      expectText(sleep, '.sleep-card__total', oracle.sleep[0])
      expectStat(sleep, 'Среднее', oracle.sleep[1])
      expectStat(sleep, 'Дней с данными', oracle.sleep[2])
      expectText(sleep, '.sleep-card__period', oracle.period)

      const steps = region('Аналитика шагов')
      const totalSteps = element(steps, '.steps-card__total')

      expect(directText(totalSteps)).toBe(oracle.steps[0])
      expect(
        within(totalSteps).getByText('шагов', { exact: true }),
      ).toBeVisible()

      expectStat(steps, 'Среднее', oracle.steps[1])
      expectStat(steps, 'Дней с данными', oracle.steps[2])
      expectText(steps, '.steps-card__period', oracle.period)

      expect(
        within(steps).getByRole('progressbar'),
      ).toHaveAttribute('aria-valuenow', oracle.coverage)

      const heart = region('Аналитика пульса')
      const pulse = element(heart, '.heart-rate-card__value')

      expect(directText(pulse)).toBe(oracle.pulse)
      expect(
        within(pulse).getByText('уд/мин', { exact: true }),
      ).toBeVisible()

      if (oracle.fixture === 'normal') {
        const time = element(heart, '.heart-rate-card__time')

        expect(normalize(time.textContent)).toContain('19.09.2026')
        expect(normalize(time.textContent)).toContain('12:15')
        expect(within(heart).getByText('В покое')).toBeVisible()
      } else {
        expectText(heart, '.heart-rate-card__time', '—')
      }

      const checkins = region('Субъективные оценки состояния')

      for (const [index, label] of [
        'Качество сна',
        'Комфорт пищеварения',
        'Самочувствие',
        'Настроение',
      ].entries()) {
        expectStat(checkins, label, oracle.scores[index])
      }
    },
  )

  it('empty: сохраняет контрактный ноль количества приёмов пищи', async () => {
    await renderResponse('empty')

    expect(
      screen.getByRole('heading', { name: 'Нет данных' }),
    ).toBeVisible()

    expectText(
      region('Количество приёмов пищи'),
      '.meal-count-card__value strong',
      '0',
    )
  })

  it('empty: сохраняет контрактный ноль дней с данными сна', async () => {
    await renderResponse('empty')

    expectStat(
      region('Аналитика сна'),
      'Дней с данными',
      '0',
    )
  })

  it('empty: сохраняет контрактный ноль дней с данными шагов', async () => {
    await renderResponse('empty')

    expectStat(
      region('Аналитика шагов'),
      'Дней с данными',
      '0',
    )
  })
})