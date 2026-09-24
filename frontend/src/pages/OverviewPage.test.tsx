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
  import OverviewPage from './OverviewPage'

  const { markSessionExpired } = vi.hoisted(() => ({
    markSessionExpired: vi.fn(),
  }))

  vi.mock('../overview/analytics', () => ({
    getAnalytics: vi.fn(),
  }))

  vi.mock('../auth/AuthProvider', () => ({
    useAuth: () => ({ markSessionExpired }),
  }))

  vi.mock('../refresh/RefreshProvider', () => ({
    useRefreshSubscription: vi.fn(),
  }))

  const getAnalyticsMock = vi.mocked(getAnalytics)
  const subscriptionMock = vi.mocked(useRefreshSubscription)

  const day = '2026-09-16'
  const missingDay = '2026-09-15'
  const emptyMessage =
    'За выбранный период нет записей для аналитики.'

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
      meal_count: { count: 0 },
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
      heart_rate: null,
      checkins: {
        sleep_quality: null,
        digestion_comfort: null,
        wellbeing: null,
        mood: null,
      },
    }
    data.series = {
      nutrition: [],
      sleep: [],
      steps: [],
      checkin: { category: 'mood', points: [] },
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

  function requestRefresh(requestedAt = Date.UTC(2026, 8, 24, 9)) {
    const call = subscriptionMock.mock.calls.at(-1)

    if (!call) {
      throw new Error('OverviewPage did not subscribe to refresh')
    }

    act(() => {
      call[0]({ requestedAt, version: 1 })
    })
  }

  function expectLoading() {
    expect(
      screen.getByRole('heading', { name: 'Загрузка аналитики' }),
    ).toBeVisible()
  }

  async function nutritionTable() {
    const chart = await screen.findByRole('region', { name: 'Питание' })
    const details = chart.querySelector('details')

    if (!details) {
      throw new Error('Nutrition chart has no daily data table')
    }

    // Такой же способ открытия details принят в charts.test.tsx.
    details.open = true

    const table = within(chart).getByRole('table')
    expect(table).toBeVisible()
    return within(table)
  }

  beforeEach(() => {
    getAnalyticsMock.mockReset()
    subscriptionMock.mockReset()
    markSessionExpired.mockReset()

    // jsdom не измеряет графики; проверяем их настоящие HTML-таблицы.
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

  describe('FE2-03 OverviewPage', () => {
    it('показывает loading до завершения запроса', async () => {
      const pending = deferred<AnalyticsResponse>()
      getAnalyticsMock.mockReturnValueOnce(pending.promise)

      render(<OverviewPage />)

      expectLoading()
      expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith({
        period: 'days_7',
      })

      await act(async () => {
        pending.resolve(response())
      })

      expect(await screen.findByRole('region', { name: 'Питание' }))
        .toBeVisible()
      expect(
        screen.queryByRole('heading', { name: 'Загрузка аналитики' }),
      ).not.toBeInTheDocument()
    })

    it('показывает общий empty и все четыре пустых графика', async () => {
      getAnalyticsMock.mockResolvedValueOnce(emptyResponse())

      render(<OverviewPage />)

      expect(await screen.findByText(emptyMessage)).toBeVisible()
      expect(
        screen.getByRole('heading', { name: 'Нет данных' }),
      ).toBeVisible()

      for (const message of [
        'Нет данных о питании за выбранный период.',
        'Нет данных о сне за выбранный период.',
        'Нет данных о шагах за выбранный период.',
        'Нет оценок «Настроение» за выбранный период.',
      ]) {
        expect(screen.getByText(message)).toBeVisible()
      }

      expect(screen.queryByRole('button', { name: /Выбрать день/ }))
        .not.toBeInTheDocument()
    })

    it.each(['empty arrays', 'null points'] as const)(
      'сохраняет данные питания при частично пустых рядах: %s',
      async (variant) => {
        const data = response()
        const emptyPoints =
          variant === 'empty arrays' ? [] : [{ date: day, value: null }]

        data.series.nutrition = [
          { date: missingDay, value: null },
          { date: day, value: 0 },
        ]
        data.series.sleep = [...emptyPoints]
        data.series.steps = [...emptyPoints]
        data.series.checkin.points = [...emptyPoints]

        getAnalyticsMock.mockResolvedValueOnce(data)

        render(<OverviewPage />)

        const table = await nutritionTable()
        const missingRow = table.getByRole('row', {
          name: `${formatTooltipDate(missingDay)} Нет данных`,
        })

        expect(within(missingRow).getByText('Нет данных')).toBeVisible()
        expect(within(missingRow).queryByRole('button'))
          .not.toBeInTheDocument()
        expect(table.getByText(formatCalories(0))).toBeVisible()
        expect(table.getAllByRole('button')).toHaveLength(1)

        expect(
          screen.getByText('Нет данных о сне за выбранный период.'),
        ).toBeVisible()
        expect(
          screen.getByText('Нет данных о шагах за выбранный период.'),
        ).toBeVisible()
        expect(
          screen.getByText('Нет оценок «Настроение» за выбранный период.'),
        ).toBeVisible()
        expect(screen.queryByText(emptyMessage)).not.toBeInTheDocument()

        fireEvent.click(table.getByRole('button'))

        expect(screen.getByText(/Выбран день:/))
          .toHaveTextContent(`Выбран день: ${day}. Показатель: Питание.`)
      },
    )

    it('показывает ошибку запроса и кнопку повторения', async () => {
      getAnalyticsMock.mockRejectedValueOnce(new Error('offline'))

      render(<OverviewPage />)

      const alert = await screen.findByRole('alert')

      expect(alert).toBeVisible()
      expect(
        within(alert).getByRole('heading', { name: 'Ошибка загрузки' }),
      ).toBeVisible()
      expect(
        within(alert).getByRole('button', { name: 'Повторить' }),
      ).toBeEnabled()
      expect(getAnalyticsMock).toHaveBeenCalledTimes(1)
      expect(markSessionExpired).not.toHaveBeenCalled()
    })

    it('повторяет запрос через «Повторить» и показывает ответ', async () => {
      const retry = deferred<AnalyticsResponse>()

      getAnalyticsMock
        .mockRejectedValueOnce(new Error('offline'))
        .mockReturnValueOnce(retry.promise)

      render(<OverviewPage />)

      fireEvent.click(
        await screen.findByRole('button', { name: 'Повторить' }),
      )

      await waitFor(() => {
        expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
      })
      expect(getAnalyticsMock).toHaveBeenNthCalledWith(2, {
        period: 'days_7',
      })
      expectLoading()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()

      await act(async () => {
        retry.resolve(response())
      })

      const table = await nutritionTable()
      expect(table.getByText(formatCalories(330))).toBeVisible()
      expect(screen.queryByRole('button', { name: 'Повторить' }))
        .not.toBeInTheDocument()
    })

    it('передаёт 401 обработчику истечения сессии', async () => {
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
        expect(markSessionExpired).toHaveBeenCalledTimes(1)
      })

      expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith({
        period: 'days_7',
      })
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Повторить' }))
        .not.toBeInTheDocument()
    })

    it('по refresh загружает новые данные и обновляет индикаторы', async () => {
      const pending = deferred<AnalyticsResponse>()
      const updated = response()
      const requestedAt = Date.UTC(2026, 8, 24, 9, 30)

      updated.series.nutrition = [{ date: day, value: 777 }]

      getAnalyticsMock
        .mockResolvedValueOnce(response())
        .mockReturnValueOnce(pending.promise)

      render(<OverviewPage />)

      const initialTable = await nutritionTable()
      expect(initialTable.getByText(formatCalories(330))).toBeVisible()
      expect(screen.getByText('Обновлений: 0')).toBeVisible()

      requestRefresh(requestedAt)

      expectLoading()
      expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
      expect(getAnalyticsMock).toHaveBeenNthCalledWith(2, {
        period: 'days_7',
      })
      expect(screen.getByText('Обновлений: 1')).toBeVisible()

      const time = new Intl.DateTimeFormat('ru-RU', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      }).format(new Date(requestedAt))

      expect(
        screen.getByText(`Последнее обновление: ${time}`),
      ).toBeVisible()

      await act(async () => {
        pending.resolve(updated)
      })

      const updatedTable = await nutritionTable()
      expect(updatedTable.getByText(formatCalories(777))).toBeVisible()
      expect(updatedTable.queryByText(formatCalories(330)))
        .not.toBeInTheDocument()
    })

    it('очищает выбранный день и не восстанавливает его после загрузки', async () => {
      const pending = deferred<AnalyticsResponse>()

      getAnalyticsMock
        .mockResolvedValueOnce(response())
        .mockReturnValueOnce(pending.promise)

      render(<OverviewPage />)

      const table = await nutritionTable()
      fireEvent.click(
        table.getByRole('button', {
          name: new RegExp(formatTooltipDate(day)),
        }),
      )

      const selection = screen.getByText(/Выбран день:/)
      expect(selection).toBeVisible()
      expect(selection).toHaveTextContent(day)

      requestRefresh()

      expectLoading()
      expect(getAnalyticsMock).toHaveBeenCalledTimes(2)
      expect(screen.queryByText(/Выбран день:/)).not.toBeInTheDocument()

      await act(async () => {
        // Тот же день остаётся в данных: отсутствие выбора означает сброс.
        pending.resolve(response())
      })

      const reloadedTable = await nutritionTable()
      expect(
        reloadedTable.getByRole('button', {
          name: new RegExp(formatTooltipDate(day)),
        }),
      ).toBeVisible()
      expect(screen.queryByText(/Выбран день:/)).not.toBeInTheDocument()
    })
  })
