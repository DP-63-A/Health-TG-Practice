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
        name: 'Loading analytics',
      }),
    ).not.toBeInTheDocument()
  })

  expect(get).toHaveBeenCalledExactlyOnceWith(
    '/analytics',
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
    nutrition: ['930 kcal', '50 g', '30 g', '110 g'],
    meals: '2',
    sleep: ['15 h', '7 h 30 min', '2'],
    steps: ['5,000', '5,000', '1'],
    coverage: '14',
    period: '13.09.2026 — 19.09.2026',
    pulse: '72',
    scores: ['4 out of 5', '5 out of 5', '3 out of 5', '4 out of 5'],
  },
  {
    fixture: 'gaps',
    nutrition: ['— kcal', '— g', '— g', '— g'],
    meals: '0',
    sleep: ['15 h', '7 h 30 min', '2'],
    steps: ['—', '—', '0'],
    coverage: '0',
    period: '13.09.2026 — 19.09.2026',
    pulse: '—',
    scores: ['—', '—', '—', '—'],
  },
  {
    fixture: 'dedup',
    nutrition: ['600 kcal', '30 g', '20 g', '70 g'],
    meals: '1',
    sleep: ['—', '—', '0'],
    steps: ['5,000', '5,000', '1'],
    coverage: '100',
    period: '19.09.2026 — 19.09.2026',
    pulse: '—',
    scores: ['—', '—', '—', '—'],
  },
  {
    fixture: 'filtered',
    nutrition: ['300 kcal', '15 g', '10 g', '35 g'],
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

      const nutrition = region('Calories & macros')

      expectText(
        nutrition,
        '.nutrition-card__energy',
        oracle.nutrition[0],
      )

      expectStat(nutrition, 'Protein', oracle.nutrition[1])
      expectStat(nutrition, 'Fat', oracle.nutrition[2])
      expectStat(nutrition, 'Carbs', oracle.nutrition[3])

      expect(
        within(nutrition).queryByRole('status'),
      ).not.toBeInTheDocument()

      expectText(
        region('Meal count'),
        '.meal-count-card__value strong',
        oracle.meals,
      )

      const sleep = region('Sleep analytics')

      expectText(sleep, '.sleep-card__total', oracle.sleep[0])
      expectStat(sleep, 'Average', oracle.sleep[1])
      expectStat(sleep, 'Days recorded', oracle.sleep[2])
      expectText(sleep, '.sleep-card__period', oracle.period)

      const steps = region('Step analytics')
      const totalSteps = element(steps, '.steps-card__total')

      expect(directText(totalSteps)).toBe(oracle.steps[0])
      expect(
        within(totalSteps).getByText('steps', { exact: true }),
      ).toBeVisible()

      expectStat(steps, 'Average', oracle.steps[1])
      expectStat(steps, 'Days recorded', oracle.steps[2])
      expectText(steps, '.steps-card__period', oracle.period)

      expect(
        within(steps).getByRole('progressbar'),
      ).toHaveAttribute('aria-valuenow', oracle.coverage)

      const heart = region('Heart rate analytics')
      const pulse = element(heart, '.heart-rate-card__value')

      expect(directText(pulse)).toBe(oracle.pulse)
      expect(
        within(pulse).getByText('bpm', { exact: true }),
      ).toBeVisible()

      if (oracle.fixture === 'normal') {
        expectText(heart, '.heart-rate-card__time', '2026-09-19 · time unknown')
        expect(within(heart).getByText('Reported at', { exact: true })).toBeVisible()
        const time = heart.querySelectorAll('.heart-rate-card__time')[1]

        expect(normalize(time.textContent)).toContain('19/09/2026')
        expect(normalize(time.textContent)).toContain('12:15')
        expect(within(heart).getByText('At rest')).toBeVisible()
      } else {
        expectText(heart, '.heart-rate-card__time', 'unknown · time unknown')
        expect(normalize(heart.querySelectorAll('.heart-rate-card__time')[1].textContent)).toBe('—')
      }

      const checkins = region('Subjective wellbeing scores')

      for (const [index, label] of [
        'Sleep quality',
        'Digestive comfort',
        'Wellbeing',
        'Mood',
      ].entries()) {
        expectStat(checkins, label, oracle.scores[index])
      }
    },
  )

  it('empty: сохраняет контрактный ноль количества meals', async () => {
    await renderResponse('empty')

    expect(
      screen.getByRole('heading', { name: 'No data' }),
    ).toBeVisible()

    expectText(
      region('Meal count'),
      '.meal-count-card__value strong',
      '0',
    )
  })

  it('empty: сохраняет контрактный ноль days с данными сна', async () => {
    await renderResponse('empty')

    expectStat(
      region('Sleep analytics'),
      'Days recorded',
      '0',
    )
  })

  it('empty: сохраняет контрактный ноль days с данными steps', async () => {
    await renderResponse('empty')

    expectStat(
      region('Step analytics'),
      'Days recorded',
      '0',
    )
  })
})
