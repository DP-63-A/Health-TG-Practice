
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import {
  createMemoryRouter,
  RouterProvider,
} from 'react-router-dom'
import {
  afterEach,
  expect,
  it,
  vi,
} from 'vitest'

import { entriesApi } from '../api/entries'
import { getAnalytics } from '../overview/analytics'
import { analyticsFixture } from '../overview/fixtures/analytics.fixture'
import { RefreshProvider } from '../refresh/RefreshProvider'
import { appRoutes } from './router'

vi.mock('../overview/analytics', () => ({
  getAnalytics: vi.fn(),
}))

const auth = vi.hoisted(() => ({
  state: {
    status: 'authenticated' as const,
    user: {
      id: 'navigation-test-user',
      timezone: 'Europe/Warsaw',
      stand_access: true,
    },
  },
  markSessionExpired: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => auth,
}))


const getAnalyticsMock = vi.mocked(getAnalytics)

afterEach(() => {
  getAnalyticsMock.mockReset()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it(
  'сохраняет период и категорию при переходе через верхнюю навигацию',
  async () => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = vi.fn()
        unobserve = vi.fn()
        disconnect = vi.fn()
      },
    )

    // Ответы предназначены для проверки навигации.
    // Это не дополнительные численные эталоны BE3.
    getAnalyticsMock.mockImplementation(async (query) => {
      const data = structuredClone(analyticsFixture)

      data.period =
        query.period === 'days_21'
          ? {
              kind: 'days_21',
              from: '2026-08-30',
              to: '2026-09-19',
              timezone: 'Europe/Warsaw',
            }
          : {
              kind: 'days_7',
              from: '2026-09-13',
              to: '2026-09-19',
              timezone: 'Europe/Warsaw',
            }

      data.observations.days_in_period =
        query.period === 'days_21' ? 21 : 7

      data.series.checkin.category = 'wellbeing'

      return data
    })

    vi.spyOn(entriesApi, 'list').mockResolvedValue({
      items: [],
      next_cursor: null,
    })

    const router = createMemoryRouter(appRoutes, {
      initialEntries: [
        '/overview?period=days_7&checkin_category=wellbeing',
      ],
    })

    const view = render(
      <RefreshProvider>
        <RouterProvider router={router} />
      </RefreshProvider>,
    )

    try {
      await screen.findByRole('region', {
        name: 'Состояние',
      })

      expect(
        screen.getByLabelText('Период'),
      ).toHaveValue('days_7')

      fireEvent.change(
        screen.getByLabelText('Период'),
        {
          target: { value: 'days_21' },
        },
      )

      await waitFor(() => {
        expect(
          getAnalyticsMock,
        ).toHaveBeenNthCalledWith(2, {
          period: 'days_21',
          timezone: 'Europe/Warsaw',
          checkin_category: 'wellbeing',
        })
      })

      await screen.findByRole('region', {
        name: 'Состояние',
      })

      expect(
        screen.getByLabelText('Период'),
      ).toHaveDisplayValue('21 день')

      const navigation = () =>
        within(
          screen.getByRole('navigation', {
            name: 'Основная навигация',
          }),
        )

      fireEvent.click(
        navigation().getByRole('link', {
          name: 'Дневник',
        }),
      )

      await screen.findByRole('heading', {
        name: 'Дневник',
      })

      expect(router.state.location.pathname).toBe('/diary')

      expect(router.state.location.state).toEqual({
        overviewReturnTo:
          '/overview?period=days_21&checkin_category=wellbeing',
      })

      const overviewLink =
        navigation().getByRole('link', {
          name: 'Обзор',
        })

      expect(overviewLink).toHaveAttribute(
        'href',
        '/overview?period=days_21&checkin_category=wellbeing',
      )

      fireEvent.click(overviewLink)

      await screen.findByRole('region', {
        name: 'Состояние',
      })

      expect(router.state.location.pathname).toBe('/overview')

      expect(router.state.location.search).toBe(
        '?period=days_21&checkin_category=wellbeing',
      )

      expect(
        screen.getByLabelText('Период'),
      ).toHaveValue('days_21')

      expect(
        screen.getByLabelText('Период'),
      ).toHaveDisplayValue('21 день')

      expect(
        screen.getByLabelText('Категория'),
      ).toHaveValue('wellbeing')

      expect(
        getAnalyticsMock,
      ).toHaveBeenNthCalledWith(3, {
        period: 'days_21',
        timezone: 'Europe/Warsaw',
        checkin_category: 'wellbeing',
      })

      expect(getAnalyticsMock).toHaveBeenCalledTimes(3)
    } finally {
      view.unmount()
      router.dispose()
    }
  },
)