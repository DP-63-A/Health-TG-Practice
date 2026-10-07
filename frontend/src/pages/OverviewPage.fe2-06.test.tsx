import {
  act,
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
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import { apiClient } from '../api/client'
import type { Entry } from '../api/types'
import type { AnalyticsResponse } from '../overview/analytics.types'
import { RefreshProvider } from '../refresh/RefreshProvider'
import { appRoutes } from '../router/router'

// ?raw поддерживается существующим vite/client.
// Настройки TypeScript и Vitest менять не нужно.
import normalJson from '../overview/fixtures/contracts/analytics_expected_normal.json?raw'
import emptyJson from '../overview/fixtures/contracts/analytics_expected_empty.json?raw'
import gapsJson from '../overview/fixtures/contracts/analytics_expected_gaps.json?raw'

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

// Ответы читаются целиком из BE3 fixtures.
// Агрегаты, средние и дневные итоги здесь не вычисляются.
function response(
  fixture: 'normal' | 'empty' | 'gaps',
): AnalyticsResponse {
  const fixtures = {
    normal: normalJson,
    empty: emptyJson,
    gaps: gapsJson,
  }

  return JSON.parse(fixtures[fixture]) as AnalyticsResponse
}

function deferred<T>() {
  let resolve!: (value: T) => void

  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })

  return { promise, resolve }
}

type TestRouter = ReturnType<typeof createMemoryRouter>

const routers: TestRouter[] = []

function renderOverview(period: 'today' | 'days_7' = 'days_7') {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [
      `/overview?period=${period}&checkin_category=mood`,
    ],
  })

  routers.push(router)

  render(
    <RefreshProvider>
      <RouterProvider router={router} />
    </RefreshProvider>,
  )

  return router
}

function analyticsRequest(period: 'today' | 'days_7') {
  return {
    query: {
      period,
      timezone: 'Europe/Warsaw',
      checkin_category: 'mood',
    },
  }
}

function changePeriod(period: 'today' | 'days_7') {
  fireEvent.change(screen.getByLabelText('Период'), {
    target: { value: period },
  })
}

function expectLoading() {
  expect(
    screen.getByRole('heading', {
      name: 'Загрузка аналитики',
    }),
  ).toBeVisible()

  expect(
    screen.queryByRole('heading', { name: 'Нет данных' }),
  ).not.toBeInTheDocument()

  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
}

async function chartTable(name: string) {
  const chart = await screen.findByRole('region', { name })
  const details = chart.querySelector('details')

  if (!details) {
    throw new Error(`В блоке "${name}" отсутствует таблица дней`)
  }

  fireEvent.click(within(chart).getByText('Значения по дням'))

  expect(details).toHaveAttribute('open')

  const table = within(chart).getByRole('table')
  expect(table).toBeVisible()

  return within(table)
}

function expectNormalOverview() {
  expect(
    screen.getByRole('heading', { name: 'Обзор' }),
  ).toBeVisible()

  expect(screen.getByLabelText('Период')).toHaveValue('days_7')
  expect(
    screen.getByLabelText('Период'),
  ).toHaveDisplayValue('7 дней')

  const nutrition = within(
    screen.getByRole('region', { name: 'Калории и БЖУ' }),
  )

  expect(nutrition.getByText('930 ккал')).toBeVisible()

  const meals = within(
    screen.getByRole('region', {
      name: 'Количество приёмов пищи',
    }),
  )

  expect(meals.getByText('2', { exact: true })).toBeVisible()

  const sleep = within(
    screen.getByRole('region', { name: 'Аналитика сна' }),
  )

  // Буквальные ожидания отображения, без production-formatter.
  expect(sleep.getByText('15 ч', { exact: true })).toBeVisible()
  expect(
    sleep.getByText('7 ч 30 мин', { exact: true }),
  ).toBeVisible()
  expect(
    sleep.getByText('13.09.2026 — 19.09.2026'),
  ).toBeVisible()

  for (const name of ['Питание', 'Сон', 'Шаги', 'Состояние']) {
    expect(screen.getByRole('region', { name })).toBeVisible()
  }

  expect(
    screen.queryByRole('heading', { name: 'Нет данных' }),
  ).not.toBeInTheDocument()

  expect(
    screen.queryByRole('heading', {
      name: 'Загрузка аналитики',
    }),
  ).not.toBeInTheDocument()

  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
}

// Только данные ответа дневника для проверки перехода.
// Это тестовый двойник записей, а не численный эталон аналитики.
function diaryEntry(
  id: string,
  type: Entry['type'],
  date: string,
): Entry {
  const payload: Entry['payload'] =
    type === 'meal'
      ? { description: 'Исходная запись питания' }
      : type === 'checkin'
        ? { category: 'mood', score: 4 }
        : {
            code: 'sleep_duration_min',
            value: 420,
            unit: 'min',
          }

  return {
    id,
    user_id: '11111111-1111-4111-8111-111111111101',
    type,
    status: 'confirmed',
    source_kind: 'seed',
    source_ref: { label: 'FE2-06 navigation fixture' },
    occurred_at: `${date}T09:00:00Z`,
    created_at: `${date}T09:00:00Z`,
    updated_at: `${date}T09:00:00Z`,
    revision: 1,
    payload,
    field_origins: {},
    submission_id: null,
  }
}

function mockGet() {
  return vi
    .spyOn(apiClient, 'get')
    .mockRejectedValue(new Error('Unexpected API request'))
}

beforeEach(() => {
  auth.markSessionExpired.mockReset()
  auth.retry.mockReset()

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
  for (const router of routers.splice(0)) {
    router.dispose()
  }

  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('FE2-06: обязательные сценарии обзора', () => {
  it('смена периода обновляет запрос, видимый период и данные', async () => {
    const next = deferred<AnalyticsResponse>()
    const get = mockGet()
      .mockResolvedValueOnce(response('empty'))
      .mockReturnValueOnce(next.promise)

    const router = renderOverview('today')

    expect(
      await screen.findByRole('heading', { name: 'Нет данных' }),
    ).toBeVisible()

    expect(get).toHaveBeenNthCalledWith(
      1,
      '/analytics',
      analyticsRequest('today'),
    )

    changePeriod('days_7')

    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))

    expect(get).toHaveBeenNthCalledWith(
      2,
      '/analytics',
      analyticsRequest('days_7'),
    )

    expect(router.state.location.search).toBe(
      '?period=days_7&checkin_category=mood',
    )

    expectLoading()
    expect(screen.queryByText('930 ккал')).not.toBeInTheDocument()

    await act(async () => {
      next.resolve(response('normal'))
    })

    expectNormalOverview()
  })

  it('пустой контрактный ответ показывает empty, а не loading/error', async () => {
    const pending = deferred<AnalyticsResponse>()
    const get = mockGet().mockReturnValueOnce(pending.promise)

    renderOverview('today')

    expectLoading()

    await act(async () => {
      pending.resolve(response('empty'))
    })

    expect(
      screen.getByRole('heading', { name: 'Нет данных' }),
    ).toBeVisible()

    expect(
      screen.queryByRole('heading', {
        name: 'Загрузка аналитики',
      }),
    ).not.toBeInTheDocument()

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    const nutrition = within(
      screen.getByRole('region', { name: 'Калории и БЖУ' }),
    )

    expect(nutrition.getByText('— ккал')).toBeVisible()
    expect(
      nutrition.queryByText('0 ккал'),
    ).not.toBeInTheDocument()

    for (const name of ['Питание', 'Сон', 'Шаги', 'Состояние']) {
      const chart = screen.getByRole('region', { name })

      expect(chart).toBeVisible()
      expect(
        within(chart).queryByRole('table'),
      ).not.toBeInTheDocument()
    }

    expect(get).toHaveBeenCalledExactlyOnceWith(
      '/analytics',
      analyticsRequest('today'),
    )
  })

  it('null-день остаётся пропуском, а контрактный ноль остаётся нулём', async () => {
    mockGet().mockResolvedValueOnce(response('gaps'))

    renderOverview()

    const sleepTable = await chartTable('Сон')

    const missingRow = sleepTable.getByRole('row', {
      name: /15\.09\.2026/,
    })

    expect(
      within(missingRow).getByRole('cell', { name: 'Нет данных' }),
    ).toBeVisible()

    expect(
      within(missingRow).queryByText('0 мин'),
    ).not.toBeInTheDocument()

    expect(
      within(missingRow).queryByText('7 ч'),
    ).not.toBeInTheDocument()

    expect(
      within(missingRow).queryByText('8 ч'),
    ).not.toBeInTheDocument()

    const firstMeasuredRow = sleepTable.getByRole('row', {
      name: /14\.09\.2026/,
    })

    expect(
      within(firstMeasuredRow).getByRole('cell', { name: '7 ч' }),
    ).toBeVisible()

    const secondMeasuredRow = sleepTable.getByRole('row', {
      name: /16\.09\.2026/,
    })

    expect(
      within(secondMeasuredRow).getByRole('cell', { name: '8 ч' }),
    ).toBeVisible()

    const nutrition = within(
      screen.getByRole('region', { name: 'Калории и БЖУ' }),
    )

    expect(nutrition.getByText('— ккал')).toBeVisible()

    const meals = within(
      screen.getByRole('region', {
        name: 'Количество приёмов пищи',
      }),
    )

    // В gaps питание отсутствует, count действительно равен 0.
    expect(meals.getByText('0', { exact: true })).toBeVisible()

    // Частично заполненный период не становится полностью пустым.
    expect(
      screen.queryByRole('heading', { name: 'Нет данных' }),
    ).not.toBeInTheDocument()
  })

  it.each([
    {
      chart: 'Питание',
      date: '2026-09-19',
      button: /Выбрать день 19\.09\.2026/,
      kind: 'nutrition',
      type: 'meal',
      sourceIds: [
        '22222222-2222-4222-8222-222222222210',
        '22222222-2222-4222-8222-222222222211',
      ],
    },
    {
      chart: 'Сон',
      date: '2026-09-14',
      button: /Выбрать день 14\.09\.2026/,
      kind: 'sleep',
      type: 'metrics',
      sourceIds: [
        '22222222-2222-4222-8222-222222222216',
      ],
    },
    {
      chart: 'Шаги',
      date: '2026-09-19',
      button: /Выбрать день 19\.09\.2026/,
      kind: 'steps',
      type: 'metrics',
      sourceIds: [
        '22222222-2222-4222-8222-222222222215',
      ],
    },
    {
      chart: 'Состояние',
      date: '2026-09-19',
      button: /Выбрать день 19\.09\.2026/,
      kind: 'checkin',
      type: 'checkin',
      sourceIds: [
        '22222222-2222-4222-8222-222222222225',
      ],
    },
  ] as const)(
    'переход из блока $chart открывает дневник с нужными фильтрами',
    async ({ chart, date, button, kind, type, sourceIds }) => {
      const get = mockGet()
        .mockResolvedValueOnce(response('normal'))
        .mockResolvedValueOnce({
          items: sourceIds.map((id) => diaryEntry(id, type, date)),
          next_cursor: null,
        })

      const router = renderOverview()
      const table = await chartTable(chart)

      fireEvent.click(table.getByRole('button', { name: button }))

      expect(
        await screen.findByRole('heading', { name: 'Дневник' }),
      ).toBeVisible()

      await waitFor(() => expect(get).toHaveBeenCalledTimes(2))

      expect(router.state.location.pathname).toBe('/diary')

      // Ожидание не строится через production buildDiaryUrl.
      expect(router.state.location.search).toBe(
        `?from=${date}&to=${date}&type=${type}`,
      )

      expect(router.state.location.state).toEqual({
        drilldown: {
          kind,
          date,
          sourceIds: [...sourceIds],
          hasValue: true,
          ...(kind === 'checkin' ? { category: 'mood' } : {}),
        },
        overviewReturnTo:
          '/overview?period=days_7&checkin_category=mood',
      })

      expect(screen.getByLabelText('С даты')).toHaveValue(date)
      expect(screen.getByLabelText('По дату')).toHaveValue(date)
      expect(screen.getByLabelText('Тип')).toHaveValue(type)
      expect(screen.getByLabelText('Режим')).toHaveValue('confirmed')

      // Проверяется настоящий вызов entriesApi через общий клиент.
      expect(get).toHaveBeenNthCalledWith(
        2,
        '/entries',
        {
          query: {
            status: 'confirmed',
            limit: 2,
            cursor: undefined,
            from: date,
            to: date,
            type,
          },
          signal: expect.any(AbortSignal),
        },
      )

      for (const id of sourceIds) {
        await waitFor(() => {
          expect(
            document.querySelector(`a[href="/diary/${id}"]`),
          ).toBeVisible()
        })
      }

      expect(
        screen.queryByRole('heading', { name: 'Записей нет' }),
      ).not.toBeInTheDocument()
    },
  )

  it('ошибка API и повтор сохраняют выбранный период и восстанавливают данные', async () => {
    const retry = deferred<AnalyticsResponse>()

    const get = mockGet()
      .mockResolvedValueOnce(response('empty'))
      .mockRejectedValueOnce(new Error('FE2-06: connection lost'))
      .mockReturnValueOnce(retry.promise)

    const router = renderOverview('today')

    await screen.findByRole('heading', { name: 'Нет данных' })

    changePeriod('days_7')

    const alert = await screen.findByRole('alert')

    expect(
      within(alert).getByRole('heading', {
        name: 'Ошибка загрузки',
      }),
    ).toBeVisible()

    expect(
      screen.queryByRole('heading', { name: 'Нет данных' }),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('heading', {
        name: 'Загрузка аналитики',
      }),
    ).not.toBeInTheDocument()

    expect(auth.markSessionExpired).not.toHaveBeenCalled()

    fireEvent.click(
      within(alert).getByRole('button', { name: 'Повторить' }),
    )

    await waitFor(() => expect(get).toHaveBeenCalledTimes(3))

    expect(get).toHaveBeenNthCalledWith(
      2,
      '/analytics',
      analyticsRequest('days_7'),
    )

    expect(get).toHaveBeenNthCalledWith(
      3,
      '/analytics',
      analyticsRequest('days_7'),
    )

    expect(router.state.location.search).toBe(
      '?period=days_7&checkin_category=mood',
    )

    expectLoading()

    await act(async () => {
      retry.resolve(response('normal'))
    })

    expectNormalOverview()

    expect(
      screen.queryByRole('button', { name: 'Повторить' }),
    ).not.toBeInTheDocument()
  })

  it('ответ A после ответа B не возвращает старый период и данные', async () => {
    const requestA = deferred<AnalyticsResponse>()
    const requestB = deferred<AnalyticsResponse>()

    const get = mockGet()
      .mockReturnValueOnce(requestA.promise)
      .mockReturnValueOnce(requestB.promise)

    const router = renderOverview('today')

    await waitFor(() => expect(get).toHaveBeenCalledTimes(1))
    expectLoading()

    changePeriod('days_7')

    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))

    expect(get).toHaveBeenNthCalledWith(
      1,
      '/analytics',
      analyticsRequest('today'),
    )

    expect(get).toHaveBeenNthCalledWith(
      2,
      '/analytics',
      analyticsRequest('days_7'),
    )

    // B завершается первым. Случайных задержек нет.
    await act(async () => {
      requestB.resolve(response('normal'))
    })

    expectNormalOverview()

    // A завершается позже. Это настоящий ответ другого периода.
    await act(async () => {
      requestA.resolve(response('empty'))
    })

    // Если защита от старого ответа сломана, появится empty,
    // исчезнут числа и диапазон дат B.
    expectNormalOverview()

    expect(router.state.location.search).toBe(
      '?period=days_7&checkin_category=mood',
    )

    expect(get).toHaveBeenCalledTimes(2)
  })
})