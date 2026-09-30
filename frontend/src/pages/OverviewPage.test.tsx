import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { DiaryDrilldown } from '../api/types'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/errors'
import { getAnalytics } from '../overview/analytics'
import type { AnalyticsResponse } from '../overview/analytics.types'
import { analyticsFixture } from '../overview/fixtures/analytics.fixture'
import { formatCalories, formatTooltipDate } from '../overview/chartFormat'
import { useRefreshSubscription } from '../refresh/RefreshProvider'
import OverviewPage, { buildDiaryUrl } from './OverviewPage'


const { markSessionExpired, navigateMock } = vi.hoisted(() => ({
  markSessionExpired: vi.fn(),
  navigateMock: vi.fn(),
}))

vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigateMock,
}))

vi.mock('../overview/analytics', () => ({
  getAnalytics: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    state: {
      status: 'authenticated',
      user: {
        id: 'test-user',
        telegram_id: 10001,
        timezone: 'Europe/Warsaw',
        stand_access: true,
      },
    },
    markSessionExpired,
  }),
}))

vi.mock('../refresh/RefreshProvider', () => ({
  useRefreshSubscription: vi.fn(),
}))

const getAnalyticsMock = vi.mocked(getAnalytics)
const subscriptionMock = vi.mocked(useRefreshSubscription)

const day = '2026-09-16'
const missingDay = '2026-09-15'

const defaultAnalyticsQuery = {
  period: 'days_7',
  timezone: 'Europe/Warsaw',
  checkin_category: 'mood',
}

const emptyMessage = 'За выбранный период нет записей для аналитики.'

function response(): AnalyticsResponse {
  return structuredClone(analyticsFixture)
}


function renderOverview(initialUrl = '/overview') {
  return render(
    <MemoryRouter initialEntries={[initialUrl]}>
      <OverviewPage />
    </MemoryRouter>,
  )
}

function expectDiaryNavigation(drilldown: DiaryDrilldown, period = 'days_7', category = 'mood') {
  expect(navigateMock).toHaveBeenCalledExactlyOnceWith(
    buildDiaryUrl(drilldown.date, drilldown.kind),
    {
      state: {
        drilldown,
        overviewReturnTo:
          '/overview?period=' + period + '&checkin_category=' + category,
      },
    },
  )
}

function emptyResponse(): AnalyticsResponse {
  const data = response()

  data.observations.days_with_any_data = 0
  data.sources = []

  data.cards = {
    nutrition: {
      energy_kcal: null,
      protein_g: null,
      fat_g: null,
      carbs_g: null,
      incomplete: false,
      meals_with_energy: 0,
    },

    meal_count: {
      count: 0,
    },

    sleep: {
      total_minutes: null,
      average_minutes: null,
      days_with_data: 0,
    },

    steps: {
      total: null,
      average: null,
      days_with_data: 0,
    },

    heart_rate: {
      value_bpm: null,
      occurred_at: null,
      qualifier: null,
      entry_id: null,
    },

    checkins: {
      sleep_quality: {
        score: null,
        date: null,
        entry_id: null,
      },

      digestion_comfort: {
        score: null,
        date: null,
        entry_id: null,
      },

      wellbeing: {
        score: null,
        date: null,
        entry_id: null,
      },

      mood: {
        score: null,
        date: null,
        entry_id: null,
      },
    },
  }

  data.series = {
    nutrition: [],
    sleep: [],
    steps: [],
    checkin: {
      category: 'mood',
      points: [],
    },
  }

  return data
}

function deferred<T>() {
  let resolve!: (value: T) => void

  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })

  return { promise, resolve }
}

function requestRefresh(
  requestedAt = Date.UTC(2026, 8, 24, 9),
) {
  const call = subscriptionMock.mock.calls.at(-1)

  if (!call) {
    throw new Error(
      'OverviewPage did not subscribe to refresh',
    )
  }

  act(() => {
    call[0]({
      requestedAt,
      version: 1,
      reason: 'manual',
    })
  })
}

function expectLoading() {
  expect(
    screen.getByRole('heading', {
      name: 'Загрузка аналитики',
    }),
  ).toBeVisible()
}

async function chartTable(name: string) {
  const chart = await screen.findByRole('region', {
    name,
  })

  const details = chart.querySelector('details')

  if (!details) {
    throw new Error(
      `У графика «${name}» нет таблицы с данными по дням`,
    )
  }

  details.open = true

  const table = within(chart).getByRole('table')

  expect(table).toBeVisible()

  return within(table)
}

beforeEach(() => {
  getAnalyticsMock.mockReset()
  subscriptionMock.mockReset()
  markSessionExpired.mockReset()
  navigateMock.mockReset()

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

describe('buildDiaryUrl', () => {
  it.each([
    ['nutrition', 'meal'],
    ['sleep', 'metrics'],
    ['steps', 'metrics'],
    ['checkin', 'checkin'],
  ] as const)(
    'формирует согласованный URL для %s',
    (kind, type) => {
      expect(buildDiaryUrl(day, kind)).toBe(
        `/diary?from=${day}&to=${day}&type=${type}`,
      )
    },
  )
})

describe('OverviewPage', () => {
  it(
    'показывает loading до завершения запроса',
    async () => {
      const pending = deferred<AnalyticsResponse>()

      getAnalyticsMock.mockReturnValueOnce(
        pending.promise,
      )

      renderOverview()

      expectLoading()

      expect(
        getAnalyticsMock,
      ).toHaveBeenCalledExactlyOnceWith(
        defaultAnalyticsQuery,
      )

      await act(async () => {
        pending.resolve(response())
      })

      expect(
        await screen.findByRole('region', {
          name: 'Питание',
        }),
      ).toBeVisible()

      expect(
        screen.queryByRole('heading', {
          name: 'Загрузка аналитики',
        }),
      ).not.toBeInTheDocument()
    },
  )

  it(
  'не оставляет данные предыдущего периода после перехода на пустой период',
  async () => {
    const initial = response()

    initial.period = {
      kind: 'days_7',
      from: '2026-09-10',
      to: day,
      timezone: 'Europe/Warsaw',
    }

    initial.observations.days_in_period = 7

    const empty = emptyResponse()

    empty.period = {
      kind: 'days_21',
      from: '2026-08-27',
      to: day,
      timezone: 'Europe/Warsaw',
    }

    empty.observations.days_in_period = 21

    getAnalyticsMock
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(empty)

    renderOverview(
      '/overview?period=days_7&checkin_category=mood',
    )

    // Старый период действительно содержит данные.
    const initialTable = await chartTable('Питание')

    expect(
      initialTable.getByText(formatCalories(930)),
    ).toBeVisible()

    // Переходим на новый период.
    fireEvent.change(
      screen.getByLabelText('Период'),
      {
        target: {
          value: 'days_21',
        },
      },
    )

    await waitFor(() => {
      expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
    })

    expect(getAnalyticsMock).toHaveBeenNthCalledWith(
      2,
      {
        ...defaultAnalyticsQuery,
        period: 'days_21',
      },
    )

    // Backend вернул пустой новый период.
    expect(
      await screen.findByRole('heading', {
        name: 'Нет данных',
      }),
    ).toBeVisible()

    expect(
      screen.getByText(emptyMessage),
    ).toBeVisible()

    // Старые точки предыдущего периода исчезли.
    expect(
      screen.queryByText(formatCalories(930)),
    ).not.toBeInTheDocument()

    expect(
      screen.getByText(
        'Нет данных о питании за выбранный период.',
      ),
    ).toBeVisible()

    expect(
      screen.getByText(
        'Нет данных о сне за выбранный период.',
      ),
    ).toBeVisible()

    expect(
      screen.getByText(
        'Нет данных о шагах за выбранный период.',
      ),
    ).toBeVisible()

    expect(
      screen.getByText(
        'Нет оценок «Настроение» за выбранный период.',
      ),
    ).toBeVisible()

    expect(
      screen.getByLabelText('Период'),
    ).toHaveValue('days_21')
  },
)

  it(
  'после ошибки refresh не показывает предыдущие данные как свежие',
  async () => {
    getAnalyticsMock
      .mockResolvedValueOnce(response())
      .mockRejectedValueOnce(new Error('offline'))

    renderOverview()

    const initialTable = await chartTable('Питание')

    expect(
      initialTable.getByText(formatCalories(930)),
    ).toBeVisible()

    requestRefresh()

    expectLoading()

    const alert = await screen.findByRole('alert')

    expect(
      within(alert).getByRole('heading', {
        name: 'Ошибка загрузки',
      }),
    ).toBeVisible()

    // Старые данные предыдущего успешного ответа
    // больше не показываются как текущие.
    expect(
      screen.queryByText(formatCalories(930)),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('region', {
        name: 'Питание',
      }),
    ).not.toBeInTheDocument()

    expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
  },
)




  it(
  'не позволяет старому ответу периода заменить более новый',
  async () => {
    const oldRequest = deferred<AnalyticsResponse>()
    const newRequest = deferred<AnalyticsResponse>()

    const oldResponse = response()
    oldResponse.period = {
      kind: 'days_7',
      from: '2026-09-10',
      to: day,
      timezone: 'Europe/Warsaw',
    }
    oldResponse.observations.days_in_period = 7
    oldResponse.cards.meal_count.count = 2

    const newResponse = response()
    newResponse.period = {
      kind: 'days_21',
      from: '2026-08-27',
      to: day,
      timezone: 'Europe/Warsaw',
    }
    newResponse.observations.days_in_period = 21
    newResponse.cards.meal_count.count = 9

    getAnalyticsMock
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise)

    renderOverview(
      '/overview?period=days_7&checkin_category=mood',
    )

    await waitFor(() => {
      expect(getAnalyticsMock).toHaveBeenCalledTimes(1)
    })

    // A = days_7 ещё выполняется.
    fireEvent.change(
      screen.getByLabelText('Период'),
      {
        target: {
          value: 'days_21',
        },
      },
    )

    await waitFor(() => {
      expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
    })

    expect(getAnalyticsMock).toHaveBeenNthCalledWith(
      2,
      {
        ...defaultAnalyticsQuery,
        period: 'days_21',
      },
    )

    // Сначала приходит новый B.
    await act(async () => {
      newRequest.resolve(newResponse)
    })

    let mealCard = within(
      await screen.findByRole('region', {
        name: 'Количество приёмов пищи',
      }),
    )

    expect(mealCard.getByText('9')).toBeVisible()

    expect(
      screen.getByLabelText('Период'),
    ).toHaveValue('days_21')

    // Потом поздно приходит старый A.
    await act(async () => {
      oldRequest.resolve(oldResponse)
    })

    await waitFor(() => {
      mealCard = within(
        screen.getByRole('region', {
          name: 'Количество приёмов пищи',
        }),
      )

      expect(mealCard.getByText('9')).toBeVisible()
      expect(
        mealCard.queryByText('2'),
      ).not.toBeInTheDocument()
    })

    expect(
      screen.getByLabelText('Период'),
    ).toHaveValue('days_21')

    expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
  },
)

  it(
    'запрашивает аналитику для выбранных периода и категории состояния',
    async () => {
      const periodResponse = response()
      periodResponse.period = {
        kind: 'days_21', from: '2026-08-27', to: day, timezone: 'Europe/Warsaw',
      }
      periodResponse.observations.days_in_period = 21
      const categoryResponse = structuredClone(periodResponse)

      categoryResponse.series.checkin.category =
        'wellbeing'

      getAnalyticsMock
        .mockResolvedValueOnce(response())
        .mockResolvedValueOnce(periodResponse)
        .mockResolvedValueOnce(categoryResponse)

      renderOverview()

      await screen.findByRole('region', {
        name: 'Питание',
      })

      fireEvent.change(
        screen.getByLabelText('Период'),
        {
          target: {
            value: 'days_21',
          },
        },
      )

      await waitFor(() => {
        expect(
          getAnalyticsMock,
        ).toHaveBeenNthCalledWith(
          2,
          {
            ...defaultAnalyticsQuery,
            period: 'days_21',
          },
        )
      })

      await screen.findByRole('region', {
        name: 'Питание',
      })

      fireEvent.change(
        screen.getByLabelText('Категория'),
        {
          target: {
            value: 'wellbeing',
          },
        },
      )

      await waitFor(() => {
        expect(
          getAnalyticsMock,
        ).toHaveBeenNthCalledWith(
          3,
          {
            ...defaultAnalyticsQuery,
            period: 'days_21',
            checkin_category: 'wellbeing',
          },
        )
      })

      expect(
        await screen.findByRole('region', {
          name: 'Питание',
        }),
      ).toBeVisible()
    },
  )

  it(
    'показывает общий empty и все четыре пустых графика',
    async () => {
      getAnalyticsMock.mockResolvedValueOnce(
        emptyResponse(),
      )

      renderOverview()

      expect(
        await screen.findByText(emptyMessage),
      ).toBeVisible()

      expect(
        screen.getByRole('heading', {
          name: 'Нет данных',
        }),
      ).toBeVisible()

      for (const message of [
        'Нет данных о питании за выбранный период.',
        'Нет данных о сне за выбранный период.',
        'Нет данных о шагах за выбранный период.',
        'Нет оценок «Настроение» за выбранный период.',
      ]) {
        expect(
          screen.getByText(message),
        ).toBeVisible()
      }

      expect(
        screen.queryByRole('button', {
          name: /Выбрать день/,
        }),
      ).not.toBeInTheDocument()

      expect(
        navigateMock,
      ).not.toHaveBeenCalled()
    },
  )

  it.each([
    'empty arrays',
    'null points',
  ] as const)(
    'сохраняет данные питания при частично пустых рядах: %s',
    async (variant) => {
      const data = response()

      data.series.nutrition = [
        {
          date: missingDay,
          energy_kcal: null,
          source: [],
        },
        {
          date: day,
          energy_kcal: 0,
          source: [],
        },
      ]

      data.series.sleep =
        variant === 'empty arrays'
          ? []
          : [
              {
                date: day,
                value: null,
                unit: 'min',
                source: null,
              },
            ]

      data.series.steps =
        variant === 'empty arrays'
          ? []
          : [
              {
                date: day,
                value: null,
                unit: 'count',
                source: null,
              },
            ]

      data.series.checkin.points =
        variant === 'empty arrays'
          ? []
          : [
              {
                date: day,
                value: null,
                unit: 'score_1_5',
                source: null,
              },
            ]

      getAnalyticsMock.mockResolvedValueOnce(
        data,
      )

      renderOverview()

      const table =
        await chartTable('Питание')

      const missingRow =
        table.getByRole('row', {
          name: /15\.09\.2026.*Нет данных/,
        })

      expect(
        within(missingRow).getByText(
          'Нет данных',
        ),
      ).toBeVisible()

      expect(
        within(missingRow).getByRole(
          'button',
        ),
      ).toBeEnabled()

      expect(
        table.getByText(
          formatCalories(0),
        ),
      ).toBeVisible()

      expect(
        table.getAllByRole('button'),
      ).toHaveLength(2)

      expect(
        screen.getByText(
          'Нет данных о сне за выбранный период.',
        ),
      ).toBeVisible()

      expect(
        screen.getByText(
          'Нет данных о шагах за выбранный период.',
        ),
      ).toBeVisible()

      expect(
        screen.getByText(
          'Нет оценок «Настроение» за выбранный период.',
        ),
      ).toBeVisible()

      expect(
        screen.queryByText(
          emptyMessage,
        ),
      ).not.toBeInTheDocument()

      fireEvent.click(
        table.getByRole('button', { name: /16\.09\.2026/ }),
      )

      expectDiaryNavigation({ kind: 'nutrition', date: day, sourceIds: [], hasValue: true })

      expect(
        screen.queryByText(/Выбран день:/),
      ).not.toBeInTheDocument()
    },
  )

  it(
    'показывает ошибку запроса и кнопку повторения',
    async () => {
      getAnalyticsMock.mockRejectedValueOnce(
        new Error('offline'),
      )

      renderOverview()

      const alert =
        await screen.findByRole('alert')

      expect(alert).toBeVisible()

      expect(
        within(alert).getByRole(
          'heading',
          { name: 'Ошибка загрузки' },
        ),
      ).toBeVisible()

      expect(
        within(alert).getByRole(
          'button',
          { name: 'Повторить' },
        ),
      ).toBeEnabled()

      expect(
        getAnalyticsMock,
      ).toHaveBeenCalledExactlyOnceWith(
        defaultAnalyticsQuery,
      )

      expect(
        markSessionExpired,
      ).not.toHaveBeenCalled()
    },
  )

  it(
    'повторяет запрос через «Повторить» и показывает ответ',
    async () => {
      const retry = deferred<AnalyticsResponse>()

      getAnalyticsMock
        .mockRejectedValueOnce(
          new Error('offline'),
        )
        .mockReturnValueOnce(
          retry.promise,
        )

      renderOverview()

      fireEvent.click(
        await screen.findByRole(
          'button',
          { name: 'Повторить' },
        ),
      )

      await waitFor(() => {
        expect(
          getAnalyticsMock,
        ).toHaveBeenCalledTimes(2)
      })

      expect(
        getAnalyticsMock,
      ).toHaveBeenNthCalledWith(
        2,
        defaultAnalyticsQuery,
      )

      expectLoading()

      expect(
        screen.queryByRole('alert'),
      ).not.toBeInTheDocument()

      await act(async () => {
        retry.resolve(response())
      })

      const table =
        await chartTable('Питание')

      expect(
        table.getByText(
          formatCalories(930),
        ),
      ).toBeVisible()

      expect(
        screen.queryByRole(
          'button',
          { name: 'Повторить' },
        ),
      ).not.toBeInTheDocument()
    },
  )

  it(
    'передаёт 401 обработчику истечения сессии',
    async () => {
      getAnalyticsMock.mockRejectedValueOnce(
        new ApiError(
          {
            code: 'session_expired',
            message: 'Session expired',
            request_id: 'overview-test',
          },
          401,
        ),
      )

      renderOverview()

      await waitFor(() => {
        expect(
          markSessionExpired,
        ).toHaveBeenCalledTimes(1)
      })

      expect(
        getAnalyticsMock,
      ).toHaveBeenCalledExactlyOnceWith(
        defaultAnalyticsQuery,
      )

      expect(
        screen.queryByRole('alert'),
      ).not.toBeInTheDocument()

      expect(
        screen.queryByRole(
          'button',
          { name: 'Повторить' },
        ),
      ).not.toBeInTheDocument()
    },
  )

  it(
    'по refresh загружает новые данные и обновляет индикаторы',
    async () => {
      const pending = deferred<AnalyticsResponse>()

      const updated = response()

      const requestedAt =
        Date.UTC(2026, 8, 24, 9, 30)

      updated.series.nutrition = [
        {
          date: day,
          energy_kcal: 777,
          source: [],
        },
      ]

      getAnalyticsMock
        .mockResolvedValueOnce(response())
        .mockReturnValueOnce(
          pending.promise,
        )

      renderOverview()

      const initialTable =
        await chartTable('Питание')

      expect(
        initialTable.getByText(
          formatCalories(930),
        ),
      ).toBeVisible()

      expect(
        screen.getByText(
          'Обновлений: 0',
        ),
      ).toBeVisible()

      requestRefresh(requestedAt)

      expectLoading()

      expect(
        getAnalyticsMock,
      ).toHaveBeenCalledTimes(2)

      expect(
        getAnalyticsMock,
      ).toHaveBeenNthCalledWith(
        2,
        defaultAnalyticsQuery,
      )

      expect(
        screen.getByText(
          'Обновлений: 1',
        ),
      ).toBeVisible()

      const time =
        new Intl.DateTimeFormat(
          'ru-RU',
          {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
          },
        ).format(
          new Date(requestedAt),
        )

      expect(
        screen.getByText(
          `Последнее обновление: ${time}`,
        ),
      ).toBeVisible()

      await act(async () => {
        pending.resolve(updated)
      })

      const updatedTable =
        await chartTable('Питание')

      expect(
        updatedTable.getByText(
          formatCalories(777),
        ),
      ).toBeVisible()

      expect(
        updatedTable.queryByText(
          formatCalories(930),
        ),
      ).not.toBeInTheDocument()
    },
  )

  it(
    'не выполняет повторный переход при refresh и новом ответе',
    async () => {
      const pending = deferred<AnalyticsResponse>()

      const initial = response()

      const selectedDay =
        initial.series.nutrition.find(
          (point) =>
            point.energy_kcal !== null,
        )?.date

      if (!selectedDay) {
        throw new Error(
          'Нет доступной точки питания в fixture',
        )
      }

      getAnalyticsMock
        .mockResolvedValueOnce(initial)
        .mockReturnValueOnce(
          pending.promise,
        )

      renderOverview()

      const table =
        await chartTable('Питание')

      fireEvent.click(
        table.getByRole('button', {
          name: new RegExp(
            formatTooltipDate(selectedDay),
          ),
        }),
      )

      expectDiaryNavigation({ kind: 'nutrition', date: selectedDay, sourceIds: [...new Set(initial.series.nutrition.find((point) => point.date === selectedDay)!.source.map((source) => source.entry_id))], hasValue: true })

      requestRefresh()

      expectLoading()

      await act(async () => {
        pending.resolve(response())
      })

      await chartTable('Питание')

      expect(
        navigateMock,
      ).toHaveBeenCalledTimes(1)
    },
  )


  it.each([
    ['Питание', 'nutrition'],
    ['Сон', 'sleep'],
    ['Шаги', 'steps'],
    ['Состояние', 'checkin'],
  ] as const)(
    'passes only the selected point sources: %s',
    async (chartName, kind) => {
      const data = response()
      const ids = {
        meal: '22222222-2222-4222-8222-222222222281',
        secondMeal: '22222222-2222-4222-8222-222222222282',
        sleep: '22222222-2222-4222-8222-222222222283',
        steps: '22222222-2222-4222-8222-222222222284',
        checkin: '22222222-2222-4222-8222-222222222285',
      }
      const source = (entry_id: string, type: 'meal' | 'metrics' | 'checkin') =>
        ({ entry_id, type, local_date: day })

      data.series = {
        nutrition: [{
          date: day, energy_kcal: 930,
          source: [source(ids.meal, 'meal'), source(ids.secondMeal, 'meal')],
        }],
        sleep: [{
          date: day, value: 450, unit: 'min',
          source: source(ids.sleep, 'metrics'),
        }],
        steps: [{
          date: day, value: 10000, unit: 'count',
          source: source(ids.steps, 'metrics'),
        }],
        checkin: {
          category: 'mood',
          points: [{
            date: day, value: 4, unit: 'score_1_5',
            source: source(ids.checkin, 'checkin'),
          }],
        },
      }
      data.sources = [
        ...data.series.nutrition[0].source,
        source(ids.sleep, 'metrics'),
        source(ids.steps, 'metrics'),
        source(ids.checkin, 'checkin'),
      ]
      data.observations.days_with_any_data = 1
      getAnalyticsMock.mockResolvedValueOnce(data)
      renderOverview()
      const table = await chartTable(chartName)
      fireEvent.click(table.getByRole('button'))
      const expectedIds = {
        nutrition: [ids.meal, ids.secondMeal],
        sleep: [ids.sleep], steps: [ids.steps], checkin: [ids.checkin],
      }
      expectDiaryNavigation({
        kind, date: day, sourceIds: expectedIds[kind], hasValue: true,
        ...(kind === 'checkin' ? { category: 'mood' as const } : {}),
      })
    },
  )

  it.each([
    'sleep_quality', 'digestion_comfort', 'wellbeing', 'mood',
  ] as const)(
    'switches category to %s and forwards its source',
    async (category) => {
      const initialCategory = category === 'mood' ? 'wellbeing' : 'mood'
      const initial = response()
      initial.series.checkin.category = initialCategory
      const selected = response()
      const sourceId = '22222222-2222-4222-8222-222222222291'
      selected.series.checkin = {
        category,
        points: [{
          date: day, value: 4, unit: 'score_1_5',
          source: { entry_id: sourceId, type: 'checkin', local_date: day },
        }],
      }
      getAnalyticsMock.mockResolvedValueOnce(initial).mockResolvedValueOnce(selected)
      renderOverview('/overview?period=days_7&checkin_category=' + initialCategory)
      const region = await screen.findByRole('region', { name: 'Состояние' })
      fireEvent.change(within(region).getByRole('combobox'), {
        target: { value: category },
      })
      await waitFor(() => {
        expect(getAnalyticsMock).toHaveBeenNthCalledWith(2, {
          ...defaultAnalyticsQuery, checkin_category: category,
        })
      })
      const table = await chartTable('Состояние')
      fireEvent.click(table.getByRole('button'))
      expectDiaryNavigation({
        kind: 'checkin', date: day, category,
        sourceIds: [sourceId], hasValue: true,
      }, 'days_7', category)
    },
  )

  it.each(['today', 'days_7', 'days_21'] as const)(
    'requests period %s and preserves it in the return URL',
    async (period) => {
      const initialPeriod = period === 'days_7' ? 'today' : 'days_7'
      const initial = response()
      initial.period.kind = initialPeriod
      const selected = response()
      selected.period = {
        kind: period,
        from: period === 'today' ? day
          : period === 'days_7' ? '2026-09-10' : '2026-08-27',
        to: day, timezone: 'Europe/Warsaw',
      }
      selected.observations.days_in_period = period === 'today' ? 1 : period === 'days_7' ? 7 : 21
      const sourceId = '22222222-2222-4222-8222-222222222292'
      selected.series.steps = [{
        date: day, value: 12345, unit: 'count',
        source: { entry_id: sourceId, type: 'metrics', local_date: day },
      }]
      getAnalyticsMock.mockResolvedValueOnce(initial).mockResolvedValueOnce(selected)
      renderOverview('/overview?period=' + initialPeriod + '&checkin_category=mood')
      await chartTable('Шаги')
      fireEvent.change(screen.getByLabelText('Период'), {
        target: { value: period },
      })
      await waitFor(() => {
        expect(getAnalyticsMock).toHaveBeenNthCalledWith(2, {
          ...defaultAnalyticsQuery, period,
        })
      })
      const table = await chartTable('Шаги')
      fireEvent.click(table.getByRole('button'))
      expectDiaryNavigation({
        kind: 'steps', date: day, sourceIds: [sourceId], hasValue: true,
      }, period)
    },
  )

  it('deduplicates nutrition source IDs', async () => {
    const data = response()
    const source = {
      entry_id: '22222222-2222-4222-8222-222222222293',
      type: 'meal' as const, local_date: day,
    }
    data.series.nutrition = [{
      date: day, energy_kcal: 330, source: [source, source],
    }]
    getAnalyticsMock.mockResolvedValueOnce(data)
    renderOverview()
    const table = await chartTable('Питание')
    fireEvent.click(table.getByRole('button'))
    expectDiaryNavigation({
      kind: 'nutrition', date: day, sourceIds: [source.entry_id], hasValue: true,
    })
  })

  it.each(['period', 'timezone'] as const)(
    'does not navigate for a mismatched response %s',
    async (mismatch) => {
      const data = response()
      if (mismatch === 'period') data.period.kind = 'today'
      else data.period.timezone = 'Asia/Tokyo'
      data.series.steps = [{
        date: day, value: 10000, unit: 'count',
        source: {
          entry_id: '22222222-2222-4222-8222-222222222294',
          type: 'metrics', local_date: day,
        },
      }]
      getAnalyticsMock.mockResolvedValueOnce(data)
      renderOverview()
      const table = await chartTable('Шаги')
      fireEvent.click(table.getByRole('button'))
      expect(navigateMock).not.toHaveBeenCalled()
    },
  )
})

describe('Overview empty-day navigation', () => {
  it.each([
    ['Питание', 'nutrition'],
    ['Сон', 'sleep'],
    ['Шаги', 'steps'],
    ['Состояние', 'checkin'],
  ] as const)(
    'opens an empty %s day and preserves overview context',
    async (chartName, kind) => {
      const data = emptyResponse()
      data.period = {
        kind: 'days_21', from: '2026-08-27', to: day,
        timezone: 'Europe/Warsaw',
      }
      data.observations.days_in_period = 21
      data.series = {
        nutrition: [{ date: day, energy_kcal: null, source: [] }],
        sleep: [{ date: day, value: null, unit: 'min', source: null }],
        steps: [{ date: day, value: null, unit: 'count', source: null }],
        checkin: {
          category: 'wellbeing',
          points: [{ date: day, value: null, unit: 'score_1_5', source: null }],
        },
      }
      getAnalyticsMock.mockResolvedValueOnce(data)
      renderOverview('/overview?period=days_21&checkin_category=wellbeing')
      const table = await chartTable(chartName)
      expect(table.getByText('Нет данных')).toBeVisible()
      fireEvent.click(table.getByRole('button'))
      expectDiaryNavigation({
        kind, date: day, sourceIds: [], hasValue: false,
        ...(kind === 'checkin' ? { category: 'wellbeing' as const } : {}),
      }, 'days_21', 'wellbeing')
    },
  )

  it('does not substitute a neighbouring nutrition source for a missing day', async () => {
    const data = response()
    data.series.nutrition = [
      { date: missingDay, energy_kcal: null, source: [] },
      {
        date: day, energy_kcal: 330,
        source: [{
          entry_id: '22222222-2222-4222-8222-222222222299',
          type: 'meal', local_date: day,
        }],
      },
    ]
    getAnalyticsMock.mockResolvedValueOnce(data)
    renderOverview()
    const table = await chartTable('Питание')
    fireEvent.click(table.getByRole('button', { name: /15\.09\.2026/ }))
    expectDiaryNavigation({
      kind: 'nutrition', date: missingDay, sourceIds: [], hasValue: false,
    })
  })
})


describe('Overview refresh: current filters and new data', () => {
  it('сохраняет выбранные фильтры и обновляет все карточки и графики', async () => {
    const initial = response()
    initial.period = {
      kind: 'days_21',
      from: '2026-08-30',
      to: '2026-09-19',
      timezone: 'Europe/Warsaw',
    }
    initial.observations.days_in_period = 21

    // Проверяем сохранение не только периода, но и категории, отличной от default.
    initial.series.checkin = {
      category: 'wellbeing',
      points: [{
        date: '2026-09-19',
        value: 3,
        unit: 'score_1_5',
        source: {
          entry_id: initial.cards.checkins.wellbeing.entry_id!,
          type: 'checkin',
          local_date: '2026-09-19',
        },
      }],
    }

    // Это готовые ответы API: серверные формулы тест не воспроизводит.
    const updated = structuredClone(initial)
    updated.cards.nutrition = {
      energy_kcal: 1200,
      protein_g: 60,
      fat_g: 40,
      carbs_g: 150,
      incomplete: false,
      meals_with_energy: 1,
    }
    updated.cards.meal_count.count = 1
    updated.cards.sleep = {
      total_minutes: 960,
      average_minutes: 480,
      days_with_data: 2,
    }
    updated.cards.steps = {
      total: 6400,
      average: 6400,
      days_with_data: 1,
    }
    updated.cards.heart_rate.value_bpm = 81
    updated.cards.checkins.sleep_quality.score = 2
    updated.cards.checkins.digestion_comfort.score = 2
    updated.cards.checkins.wellbeing.score = 2
    updated.cards.checkins.mood.score = 2

    updated.series.nutrition = [{
      ...initial.series.nutrition[0],
      energy_kcal: 1200,
      source: initial.series.nutrition[0].source.slice(0, 1),
    }]
    updated.series.sleep = [
      { ...initial.series.sleep[0], value: 450 },
      { ...initial.series.sleep[1], value: 510 },
    ]
    updated.series.steps = [{
      ...initial.series.steps[0],
      value: 6400,
    }]
    updated.series.checkin.points = [{
      ...initial.series.checkin.points[0],
      value: 2,
    }]

    const removedMealIds = new Set(
      initial.series.nutrition[0].source
        .slice(1)
        .map((source) => source.entry_id),
    )
    updated.sources = updated.sources.filter(
      (source) => !removedMealIds.has(source.entry_id),
    )
    updated.observations.generated_at = '2026-09-19T20:06:00Z'

    const pending = deferred<AnalyticsResponse>()
    getAnalyticsMock
      .mockResolvedValueOnce(initial)
      .mockReturnValueOnce(pending.promise)

    renderOverview('/overview?period=days_21&checkin_category=wellbeing')

    const initialTable = await chartTable('Питание')
    expect(initialTable.getByText(formatCalories(930))).toBeVisible()

    const region = (name: string) =>
      within(screen.getByRole('region', { name }))

    // Все блоки сначала действительно показывают первый ответ.
    expect(region('Калории и БЖУ').getByText('930 ккал')).toBeVisible()
    expect(region('Количество приёмов пищи').getByText('2')).toBeVisible()
    expect(region('Аналитика сна').getByText('15 ч')).toBeVisible()
    expect(region('Аналитика шагов').getAllByText(/^5\s?000$/)).toHaveLength(2)
    expect(region('Аналитика пульса').getByText('72')).toBeVisible()
    expect(region('Субъективные оценки состояния').getByText('3')).toBeVisible()

    const initialSleep = await chartTable('Сон')
    expect(initialSleep.getByText('7 ч')).toBeVisible()
    expect(initialSleep.getByText('8 ч')).toBeVisible()
    expect((await chartTable('Шаги')).getByText(/5\s?000 шагов/)).toBeVisible()
    expect((await chartTable('Состояние')).getByText('3 из 5')).toBeVisible()

    const query = {
      period: 'days_21',
      timezone: 'Europe/Warsaw',
      checkin_category: 'wellbeing',
    }
    expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith(query)

    requestRefresh()

    await waitFor(() => {
      expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
    })
    expect(getAnalyticsMock).toHaveBeenNthCalledWith(2, query)

    // До завершения запроса предыдущие графики скрыты.
    expectLoading()
    for (const name of ['Питание', 'Сон', 'Шаги', 'Состояние']) {
      expect(screen.queryByRole('region', { name })).not.toBeInTheDocument()
    }

    await act(async () => {
      pending.resolve(updated)
    })

    const nutritionTable = await chartTable('Питание')
    expect(nutritionTable.getByText(formatCalories(1200))).toBeVisible()
    expect(nutritionTable.queryByText(formatCalories(930))).not.toBeInTheDocument()

    const nutritionCard = region('Калории и БЖУ')
    expect(nutritionCard.getByText(/1\s?200 ккал/)).toBeVisible()
    expect(nutritionCard.getByText('60 г')).toBeVisible()
    expect(nutritionCard.getByText('40 г')).toBeVisible()
    expect(nutritionCard.getByText('150 г')).toBeVisible()
    expect(nutritionCard.queryByText('930 ккал')).not.toBeInTheDocument()

    const mealCard = region('Количество приёмов пищи')
    expect(mealCard.getByText('1')).toBeVisible()
    expect(mealCard.queryByText('2')).not.toBeInTheDocument()

    const sleepCard = region('Аналитика сна')
    expect(sleepCard.getByText('16 ч')).toBeVisible()
    expect(sleepCard.getByText('8 ч')).toBeVisible()
    expect(sleepCard.queryByText('15 ч')).not.toBeInTheDocument()

    const stepsCard = region('Аналитика шагов')
    expect(stepsCard.getAllByText(/^6\s?400$/)).toHaveLength(2)
    expect(stepsCard.queryByText(/^5\s?000$/)).not.toBeInTheDocument()

    const heartCard = region('Аналитика пульса')
    expect(heartCard.getByText('81')).toBeVisible()
    expect(heartCard.queryByText('72')).not.toBeInTheDocument()

    const checkinCard = region('Субъективные оценки состояния')
    expect(checkinCard.getAllByText('2')).toHaveLength(4)
    expect(checkinCard.queryByText('3')).not.toBeInTheDocument()
    expect(checkinCard.queryByText('4')).not.toBeInTheDocument()
    expect(checkinCard.queryByText('5')).not.toBeInTheDocument()

    const sleepTable = await chartTable('Сон')
    expect(sleepTable.getByText('7 ч 30 мин')).toBeVisible()
    expect(sleepTable.getByText('8 ч 30 мин')).toBeVisible()
    expect(sleepTable.queryByText('7 ч')).not.toBeInTheDocument()
    expect(sleepTable.queryByText('8 ч')).not.toBeInTheDocument()

    const stepsTable = await chartTable('Шаги')
    expect(stepsTable.getByText(/6\s?400 шагов/)).toBeVisible()
    expect(stepsTable.queryByText(/5\s?000 шагов/)).not.toBeInTheDocument()

    const checkinTable = await chartTable('Состояние')
    expect(checkinTable.getByText('2 из 5')).toBeVisible()
    expect(checkinTable.queryByText('3 из 5')).not.toBeInTheDocument()

    expect(screen.getByLabelText('Период')).toHaveValue('days_21')
    expect(screen.getByLabelText('Категория')).toHaveValue('wellbeing')
    expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
  })
})
