
// import {
//     act,
//     fireEvent,
//     render,
//     screen,
//     waitFor,
//     within,
//   } from '@testing-library/react'
//   import { afterEach, beforeEach, describe, expect,
//   it, vi } from 'vitest'

//   import { ApiError } from '../api/errors'
//   import { getAnalytics } from '../overview/analytics'
//   import type { AnalyticsResponse } from '../overview/analytics.types'
//   import { analyticsFixture } from '../overview/fixtures/analytics.fixture'
//   import { formatCalories, formatTooltipDate } from
//   '../overview/chartFormat'
//   import { useRefreshSubscription } from '../refresh/RefreshProvider'
//   import OverviewPage from './OverviewPage'

//   const { markSessionExpired } = vi.hoisted(() => ({
//     markSessionExpired: vi.fn(),
//   }))

//   vi.mock('../overview/analytics', () => ({
//     getAnalytics: vi.fn(),
//   }))

//   vi.mock('../auth/AuthProvider', () => ({
//     useAuth: () => ({
//       state: {
//         status: 'authenticated',
//         user: {
//           id: 'test-user',
//           telegram_id: 10001,
//           timezone: 'Europe/Warsaw',
//           stand_access: true,
//         },
//       },
//       markSessionExpired,
//     }),
//   }))

//   vi.mock('../refresh/RefreshProvider', () => ({
//     useRefreshSubscription: vi.fn(),
//   }))

//   const getAnalyticsMock = vi.mocked(getAnalytics)
//   const subscriptionMock =
//   vi.mocked(useRefreshSubscription)

//   const day = '2026-09-16'
//   const missingDay = '2026-09-15'

//   const defaultAnalyticsQuery = {
//     period: 'days_7',
//     timezone: 'Europe/Warsaw',
//     checkin_category: 'mood',
//   }

//   const emptyMessage =
//     'За выбранный период нет записей для аналитики.'

//   function response(): AnalyticsResponse {
//     return structuredClone(analyticsFixture)
//   }

//   function emptyResponse(): AnalyticsResponse {
//     const data = response()

//     data.observations.days_with_any_data = 0
//     data.sources = []
//     data.cards = {
//       nutrition: {
//         energy_kcal: null,
//         protein_g: null,
//         fat_g: null,
//         carbs_g: null,
//         incomplete: false,
//         meals_with_energy: 0,
//       },
//       meal_count: { count: 0 },
//       sleep: {
//         total_minutes: null,
//         average_minutes: null,
//         days_with_data: 0,
//       },
//       steps: {
//         total: null,
//         average: null,
//         days_with_data: 0,
//       },
//       heart_rate: {
//         value_bpm: null,
//         occurred_at: null,
//         qualifier: null,
//         entry_id: null,
//       },
//       checkins: {
//         sleep_quality: { score: null, date: null,
//         entry_id: null },
//         digestion_comfort: { score: null, date: null,
//         entry_id: null },
//         wellbeing: { score: null, date: null,
//         entry_id: null },
//         mood: { score: null, date: null, entry_id:
//         null },
//       },
//     }
//     data.series = {
//       nutrition: [],
//       sleep: [],
//       steps: [],
//       checkin: { category: 'mood', points: [] },
//     }

//     return data
//   }

//   function deferred<T>() {
//     let resolve!: (value: T) => void

//     const promise = new Promise<T>((resolvePromise) => {
//       resolve = resolvePromise
//     })

//     return { promise, resolve }
//   }

//   function requestRefresh(requestedAt =
//   Date.UTC(2026, 8, 24, 9)) {
//     const call = subscriptionMock.mock.calls.at(-1)

//     if (!call) {
//       throw new Error('OverviewPage did not subscribeto refresh')
//     }

//     act(() => {
//       call[0]({ requestedAt, version: 1, reason:
//       'manual' })
//     })
//   }

//   function expectLoading() {
//     expect(
//       screen.getByRole('heading', { name: 'Загрузка аналитики' }),
//     ).toBeVisible()
//   }

//   async function nutritionTable() {
//     const chart = await screen.findByRole('region',
//     { name: 'Питание' })
//     const details = chart.querySelector('details')

//     if (!details) {
//       throw new Error('Nutrition chart has no daily data table')
//     }

//     details.open = true

//     const table = within(chart).getByRole('table')
//     expect(table).toBeVisible()
//     return within(table)
//   }

//   beforeEach(() => {
//     getAnalyticsMock.mockReset()
//     subscriptionMock.mockReset()
//     markSessionExpired.mockReset()

//     vi.stubGlobal(
//       'ResizeObserver',
//       class {
//         observe = vi.fn()
//         unobserve = vi.fn()
//         disconnect = vi.fn()
//       },
//     )
//   })

//   afterEach(() => {
//     vi.unstubAllGlobals()
//   })

//   describe('OverviewPage', () => {
//     it('показывает loading до завершения запроса',
//     async () => {
//       const pending = deferred<AnalyticsResponse>()

//       getAnalyticsMock.mockReturnValueOnce(pending.promise)

//       render(<OverviewPage />)

//       expectLoading()

//       expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith(
//         defaultAnalyticsQuery,
//       )

//       await act(async () => {
//         pending.resolve(response())
//       })

//       expect(
//         await screen.findByRole('region', { name:
//         'Питание' }),
//       ).toBeVisible()
//       expect(
//         screen.queryByRole('heading', { name:
//         'Загрузка аналитики' }),
//       ).not.toBeInTheDocument()
//     })

//     it('запрашивает аналитику для выбранных периода и категории состояния', async () => {
//       const initialResponse = response()
//       const periodResponse = response()
//       const categoryResponse = response()
//       categoryResponse.series.checkin.category =
//       'wellbeing'

//       getAnalyticsMock
//         .mockResolvedValueOnce(initialResponse)
//         .mockResolvedValueOnce(periodResponse)
//         .mockResolvedValueOnce(categoryResponse)

//       render(<OverviewPage />)

//       await screen.findByRole('region', { name:
//       'Питание' })


//       fireEvent.change(screen.getByLabelText('Период'
//       ), {
//         target: { value: 'days_21' },
//       })

//       await waitFor(() => {

//         expect(getAnalyticsMock).toHaveBeenNthCalledWith(2, {
//           ...defaultAnalyticsQuery,
//           period: 'days_21',
//         })
//       })

//       await screen.findByRole('region', { name:
//       'Питание' })


//       fireEvent.change(screen.getByLabelText('Категория'), {
//         target: { value: 'wellbeing' },
//       })

//       await waitFor(() => {

//         expect(getAnalyticsMock).toHaveBeenNthCalledWith(3, {
//           ...defaultAnalyticsQuery,
//           period: 'days_21',
//           checkin_category: 'wellbeing',
//         })
//       })

//       expect(
//         await screen.findByRole('region', { name:
//         'Питание' }),
//       ).toBeVisible()
//     })

//     it('показывает общий empty и все четыре пустых графика', async () => {

//       getAnalyticsMock.mockResolvedValueOnce(emptyResponse())

//       render(<OverviewPage />)

//       expect(await
//       screen.findByText(emptyMessage)).toBeVisible()
//       expect(
//         screen.getByRole('heading', { name: 'Нет данных' }),
//       ).toBeVisible()

//       for (const message of [
//         'Нет данных о питании за выбранный период.',
//         'Нет данных о сне за выбранный период.',
//         'Нет данных о шагах за выбранный период.',
//         'Нет оценок «Настроение» за выбранный период.',
//       ]) {

//         expect(screen.getByText(message)).toBeVisible
//         ()
//       }

//       expect(
//         screen.queryByRole('button', { name: /Выбрать день/ }),
//       ).not.toBeInTheDocument()
//     })

//     it.each(['empty arrays', 'null points'] as const)
//     (
//       'сохраняет данные питания при частично пустых рядах: %s',
//       async (variant) => {
//         const data = response()

//         const emptySleepPoints =
//           variant === 'empty arrays'
//             ? []
//             : [{ date: day, value: null, unit: 'min' as const, source: null }]

//         const emptyStepsPoints =
//           variant === 'empty arrays'
//             ? []
//             : [{ date: day, value: null, unit:
//             'count' as const, source: null }]

//         const emptyCheckinPoints =
//           variant === 'empty arrays'
//             ? []
//             : [
//                 {
//                   date: day,
//                   value: null,
//                   unit: 'score_1_5' as const,
//                   source: null,
//                 },
//               ]

//         data.series.nutrition = [
//           { date: missingDay, energy_kcal: null,
//           source: [] },
//           { date: day, energy_kcal: 0, source: [] },
//         ]
//         data.series.sleep = emptySleepPoints
//         data.series.steps = emptyStepsPoints
//         data.series.checkin.points =
//         emptyCheckinPoints

//         getAnalyticsMock.mockResolvedValueOnce(data)

//         render(<OverviewPage />)

//         const table = await nutritionTable()
//         const missingRow = table.getByRole('row', {
//           name: `${formatTooltipDate(missingDay)} Нет
//           данных`,
//         })

//         expect(within(missingRow).getByText('Нет данных')).toBeVisible()

//         expect(within(missingRow).queryByRole('button'))
//           .not.toBeInTheDocument()

//         expect(table.getByText(formatCalories(0))).toBeVisible()

//         expect(table.getAllByRole('button')).toHaveLength(1)

//         expect(
//           screen.getByText('Нет данных о сне за выбранный период.'),
//         ).toBeVisible()
//         expect(
//           screen.getByText('Нет данных о шагах за выбранный период.'),
//         ).toBeVisible()
//         expect(
//           screen.getByText('Нет оценок «Настроение»за выбранный период.'),
//         ).toBeVisible()

//         expect(screen.queryByText(emptyMessage)).not.
//         toBeInTheDocument()

//         fireEvent.click(table.getByRole('button'))

//         expect(screen.getByText(/Выбран день:/)).toHaveTextContent(
//           `Выбран день: ${day}. Показатель:
//           Питание.`,
//         )
//       },
//     )

//     it('показывает ошибку запроса и кнопку повторения', async () => {
//       getAnalyticsMock.mockRejectedValueOnce(new
//       Error('offline'))

//       render(<OverviewPage />)

//       const alert = await screen.findByRole('alert')

//       expect(alert).toBeVisible()
//       expect(
//         within(alert).getByRole('heading', { name:
//         'Ошибка загрузки' }),
//       ).toBeVisible()
//       expect(
//         within(alert).getByRole('button', { name:
//         'Повторить' }),
//       ).toBeEnabled()

//       expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith(
//         defaultAnalyticsQuery,
//       )

//       expect(markSessionExpired).not.toHaveBeenCalled
//       ()
//     })

//     it('повторяет запрос через «Повторить» ипоказывает ответ', async () => {
//       const retry = deferred<AnalyticsResponse>()

//       getAnalyticsMock
//         .mockRejectedValueOnce(new Error('offline'))
//         .mockReturnValueOnce(retry.promise)

//       render(<OverviewPage />)

//       fireEvent.click(
//         await screen.findByRole('button', { name:
//         'Повторить' }),
//       )

//       await waitFor(() => {

//         expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
//       })

//       expect(getAnalyticsMock).toHaveBeenNthCalledWith(
//         2,
//         defaultAnalyticsQuery,
//       )
//       expectLoading()

//       expect(screen.queryByRole('alert')).not.toBeInTheDocument()

//       await act(async () => {
//         retry.resolve(response())
//       })

//       const table = await nutritionTable()

//       expect(table.getByText(formatCalories(930))).toBeVisible()
//       expect(
//         screen.queryByRole('button', { name:
//         'Повторить' }),
//       ).not.toBeInTheDocument()
//     })

//     it('передаёт 401 обработчику истечения сессии',
//     async () => {
//       getAnalyticsMock.mockRejectedValueOnce(
//         new ApiError(
//           {
//             code: 'session_expired',
//             message: 'Session expired',
//             request_id: 'overview-test',
//           },
//           401,
//         ),
//       )

//       render(<OverviewPage />)

//       await waitFor(() => {

//         expect(markSessionExpired).toHaveBeenCalledTimes(1)
//       })


//       expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith(
//         defaultAnalyticsQuery,
//       )

//       expect(screen.queryByRole('alert')).not.toBeInTheDocument()
//       expect(
//         screen.queryByRole('button', { name:
//         'Повторить' }),
//       ).not.toBeInTheDocument()
//     })

//     it('по refresh загружает новые данные и обновляет индикаторы', async () => {
//       const pending = deferred<AnalyticsResponse>()
//       const updated = response()
//       const requestedAt = Date.UTC(2026, 8, 24, 9, 30)

//       updated.series.nutrition = [
//         { date: day, energy_kcal: 777, source: [] },
//       ]

//       getAnalyticsMock
//         .mockResolvedValueOnce(response())
//         .mockReturnValueOnce(pending.promise)

//       render(<OverviewPage />)

//       const initialTable = await nutritionTable()

//       expect(initialTable.getByText(formatCalories(930))).toBeVisible()
//       expect(screen.getByText('Обновлений:0')).toBeVisible()

//       requestRefresh(requestedAt)

//       expectLoading()

//       expect(getAnalyticsMock).toHaveBeenCalledTimes(
//       2)

//       expect(getAnalyticsMock).toHaveBeenNthCalledWith(
//         2,
//         defaultAnalyticsQuery,
//       )
//       expect(screen.getByText('Обновлений:1')).toBeVisible()

//       const time = new Intl.DateTimeFormat('ru-RU', {
//         hour: '2-digit',
//         minute: '2-digit',
//         second: '2-digit',
//       }).format(new Date(requestedAt))

//       expect(
//         screen.getByText(`Последнее обновление:
//         ${time}`),
//       ).toBeVisible()

//       await act(async () => {
//         pending.resolve(updated)
//       })

//       const updatedTable = await nutritionTable()

//       expect(updatedTable.getByText(formatCalories(777))).toBeVisible()

//       expect(updatedTable.queryByText(formatCalories(
//       930)))
//         .not.toBeInTheDocument()
//     })

//     it('очищает выбранный день и не восстанавливает его после загрузки', async () => {
// const pending = deferred<AnalyticsResponse>()

//       getAnalyticsMock
//         .mockResolvedValueOnce(response())
//         .mockReturnValueOnce(pending.promise)

//       render(<OverviewPage />)

//       const table = await nutritionTable()
//       fireEvent.click(
//         table.getByRole('button', {
//           name: new RegExp(formatTooltipDate(day)),
//         }),
//       )

//       const selection = screen.getByText(/Выбран день:/)
//       expect(selection).toBeVisible()
//       expect(selection).toHaveTextContent(day)

//       requestRefresh()

//       expectLoading()

//       expect(getAnalyticsMock).toHaveBeenCalledTimes(
//       2)
//       expect(screen.queryByText(/Выбран день:/)).not.toBeInTheDocument()

//       await act(async () => {
//         pending.resolve(response())
//       })

//       const reloadedTable = await nutritionTable()
//       expect(
//         reloadedTable.getByRole('button', {
//           name: new RegExp(formatTooltipDate(day)),
//         }),
//       ).toBeVisible()
//       expect(screen.queryByText(/Выбран день:/)).not.toBeInTheDocument()
//     })
//   })







import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
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

      render(<OverviewPage />)

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
    'запрашивает аналитику для выбранных периода и категории состояния',
    async () => {
      const categoryResponse = response()

      categoryResponse.series.checkin.category =
        'wellbeing'

      getAnalyticsMock
        .mockResolvedValueOnce(response())
        .mockResolvedValueOnce(response())
        .mockResolvedValueOnce(categoryResponse)

      render(<OverviewPage />)

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

      render(<OverviewPage />)

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

      render(<OverviewPage />)

      const table =
        await chartTable('Питание')

      const missingRow =
        table.getByRole('row', {
          name:
            `${formatTooltipDate(missingDay)} Нет данных`,
        })

      expect(
        within(missingRow).getByText(
          'Нет данных',
        ),
      ).toBeVisible()

      expect(
        within(missingRow).queryByRole(
          'button',
        ),
      ).not.toBeInTheDocument()

      expect(
        table.getByText(
          formatCalories(0),
        ),
      ).toBeVisible()

      expect(
        table.getAllByRole('button'),
      ).toHaveLength(1)

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
        table.getByRole('button'),
      )

      expect(
        navigateMock,
      ).toHaveBeenCalledExactlyOnceWith(
        `/diary?from=${day}&to=${day}&type=meal`,
      )

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

      render(<OverviewPage />)

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

      render(<OverviewPage />)

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

      render(<OverviewPage />)

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

      render(<OverviewPage />)

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

      render(<OverviewPage />)

      const table =
        await chartTable('Питание')

      fireEvent.click(
        table.getByRole('button', {
          name: new RegExp(
            formatTooltipDate(selectedDay),
          ),
        }),
      )

      expect(
        navigateMock,
      ).toHaveBeenCalledExactlyOnceWith(
        `/diary?from=${selectedDay}&to=${selectedDay}&type=meal`,
      )

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
    ['Питание', 'nutrition', 'meal'],
    ['Сон', 'sleep', 'metrics'],
    ['Шаги', 'steps', 'metrics'],
    ['Состояние', 'checkin', 'checkin'],
  ] as const)(
    'при нажатии на график «%s» открывает Diary с нужной датой и типом',
    async (chartName, kind, type) => {
      const data = response()

      data.series.nutrition = []
      data.series.sleep = []
      data.series.steps = []
      data.series.checkin.points = []

      data.observations.days_with_any_data = 1

      if (kind === 'nutrition') {
        data.series.nutrition = [
          {
            date: day,
            energy_kcal: 930,
            source: [],
          },
        ]
      } else if (kind === 'sleep') {
        data.series.sleep = [
          {
            date: day,
            value: 450,
            unit: 'min',
            source: null,
          },
        ]
      } else if (kind === 'steps') {
        data.series.steps = [
          {
            date: day,
            value: 5000,
            unit: 'count',
            source: null,
          },
        ]
      } else {
        data.series.checkin = {
          category: 'mood',
          points: [
            {
              date: day,
              value: 4,
              unit: 'score_1_5',
              source: null,
            },
          ],
        }
      }

      getAnalyticsMock.mockResolvedValueOnce(
        data,
      )

      render(<OverviewPage />)

      const table =
        await chartTable(chartName)

      const buttons =
        table.getAllByRole('button')

      expect(buttons).toHaveLength(1)

      fireEvent.click(buttons[0])

      expect(
        navigateMock,
      ).toHaveBeenCalledExactlyOnceWith(
        `/diary?from=${day}&to=${day}&type=${type}`,
      )

      expect(
        screen.queryByText(/Выбран день:/),
      ).not.toBeInTheDocument()
    },
  )
})