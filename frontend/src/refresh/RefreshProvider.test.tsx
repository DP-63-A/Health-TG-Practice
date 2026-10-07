import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode, useState } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { entriesApi } from '../api/entries'
import { analyticsFixture } from '../overview/fixtures/analytics.fixture'
import * as analytics from '../overview/analytics'
import { appRoutes } from '../router/router'
import { AuthProvider } from '../auth/AuthProvider'
import { AuthGate } from '../auth/AuthGate'
import { RefreshProvider, useRefresh, useRefreshSubscription } from './RefreshProvider'

describe('refresh mechanism', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('calls refresh subscribers when refresh is requested', async () => {
    render(
      <RefreshProvider>
        <RefreshProbe />
        <RequestRefreshButton />
      </RefreshProvider>,
    )

    expect(screen.getByText('Refresh events: 0')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))

    expect(screen.getByText('Refresh events: 1')).toBeInTheDocument()
  })

  it('DiaryPage receives refresh event', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))

    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
  })

  it('OverviewPage receives refresh event', async () => {
    const getAnalytics = vi.spyOn(analytics, 'getAnalytics').mockResolvedValue(analyticsFixture)
    renderRoute('/overview')

    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))

    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(2))
  })

  it('Overview rereads analytics on the same mutation signal used by EntryPage', async () => {
    const getAnalytics = vi.spyOn(analytics, 'getAnalytics').mockResolvedValue(analyticsFixture)
    renderRoute('/overview', true)
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Mutation refresh' }))
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(2))
  })

  it('notifies multiple subscribers once after one request', () => {
    render(<RefreshProvider><RefreshProbe /><RefreshProbe /><RequestRefreshButton /></RefreshProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(screen.getAllByText('Refresh events: 1')).toHaveLength(2)
  })

  it('rereads Diary once when the app returns from the background', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary')
    await screen.findByText('Овсянка с ягодами')
    expect(list).toHaveBeenCalledTimes(1)

    fireEvent(window, new Event('blur'))
    fireEvent(window, new Event('focus'))
    fireEvent(document, new Event('visibilitychange'))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    fireEvent(window, new Event('focus'))
    expect(list).toHaveBeenCalledTimes(2)
  })

  it('uses visibilitychange when a hidden Mini App becomes visible', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState')
    const list = vi.spyOn(entriesApi, 'list')
    try {
      renderRoute('/diary')
      await screen.findByText('Овсянка с ягодами')
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
      fireEvent(document, new Event('visibilitychange'))
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
      fireEvent(document, new Event('visibilitychange'))
      fireEvent(window, new Event('focus'))
      await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    } finally {
      if (descriptor) Object.defineProperty(document, 'visibilityState', descriptor)
      else Reflect.deleteProperty(document, 'visibilityState')
    }
  })

  it('coalesces visibility, focus, and persisted pageshow for one resume', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState')
    const list = vi.spyOn(entriesApi, 'list')
    try {
      renderRoute('/diary')
      await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-09-25T10:00:00Z'))
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
      fireEvent(window, new Event('pagehide'))
      fireEvent(document, new Event('visibilitychange'))
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
      fireEvent(window, new Event('focus'))
      fireEvent(document, new Event('visibilitychange'))
      fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }))
      await act(async () => { vi.advanceTimersByTime(100) })
      expect(list).toHaveBeenCalledTimes(2)
      fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }))
      await act(async () => { vi.advanceTimersByTime(160) })
      expect(list).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
      if (descriptor) Object.defineProperty(document, 'visibilityState', descriptor)
      else Reflect.deleteProperty(document, 'visibilityState')
    }
  })

  it('cleans lifecycle listeners and pending resume on unmount', async () => {
    const onRefresh = vi.fn()
    const view = render(<StrictMode><RefreshProvider><RefreshCallback onRefresh={onRefresh} /></RefreshProvider></StrictMode>)
    try {
      vi.useFakeTimers()
      fireEvent(window, new Event('blur'))
      fireEvent(window, new Event('focus'))
      view.unmount()
      await act(async () => { vi.advanceTimersByTime(160) })
      fireEvent(window, new Event('focus'))
      expect(onRefresh).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('ignores an older Overview response after a newer shared refresh', async () => {
    let resolveOld: (value: typeof analyticsFixture) => void = () => undefined
    let resolveNew: (value: typeof analyticsFixture) => void = () => undefined
    const getAnalytics = vi.spyOn(analytics, 'getAnalytics')
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve }))
    renderRoute('/overview')
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(2))
    resolveNew({ ...analyticsFixture, cards: { ...analyticsFixture.cards, meal_count: { count: 9 } } })
    const card = await screen.findByRole('region', { name: 'Meal count' })
    expect(within(card).getByText('9')).toBeInTheDocument()
    await act(async () => resolveOld(analyticsFixture))
    expect(within(card).getByText('9')).toBeInTheDocument()
    expect(within(card).queryByText('2')).not.toBeInTheDocument()
  })

  it.each(['manual', 'mutation', 'lifecycle'] as const)(
    'обновляет обзор по сигналу %s с сохранением периода и категории',
    async (reason) => {
      const initial = structuredClone(analyticsFixture)
      initial.period = {
        kind: 'days_21',
        from: '2026-08-30',
        to: '2026-09-19',
        timezone: 'Europe/Warsaw',
      }
      initial.observations.days_in_period = 21
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

      const updated = structuredClone(initial)
      updated.cards.heart_rate.value_bpm = 81
      updated.observations.generated_at = '2026-09-19T20:06:00Z'

      let resolveRefresh!: (value: typeof analyticsFixture) => void
      const pending = new Promise<typeof analyticsFixture>((resolve) => {
        resolveRefresh = resolve
      })
      const getAnalytics = vi.spyOn(analytics, 'getAnalytics')
        .mockResolvedValueOnce(initial)
        .mockReturnValueOnce(pending)

      const visibilityDescriptor = Object.getOwnPropertyDescriptor(
        document, 'visibilityState',
      )
      const router = createMemoryRouter(appRoutes, {
        initialEntries: [
          '/overview?period=days_21&checkin_category=wellbeing',
        ],
      })

      // Как в main.tsx: обзор монтируется после получения пользователя.
      // Мокаем только ответ аналитики; общий refresh и router настоящие.
      const view = render(
        <AuthProvider>
          <AuthGate>
            <RefreshProvider>
              {reason === 'mutation' && <MutationRefreshButton />}
              <RouterProvider router={router} />
            </RefreshProvider>
          </AuthGate>
        </AuthProvider>,
      )

      try {
        const heartCard = await screen.findByRole('region', {
          name: 'Heart rate analytics',
        })
        await waitFor(() => {
          expect(within(heartCard).getByText('72')).toBeVisible()
        })

        const query = {
          period: 'days_21',
          timezone: 'Europe/Warsaw',
          checkin_category: 'wellbeing',
        }
        expect(getAnalytics).toHaveBeenCalledExactlyOnceWith(query)

        if (reason === 'manual') {
          fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
        } else if (reason === 'mutation') {
          // Сигнал FE1 после успешной мутации; саму HTTP-операцию
          // отдельно проверяют тесты EntryPage.
          fireEvent.click(
            screen.getByRole('button', { name: 'Mutation refresh' }),
          )
        } else {
          Object.defineProperty(document, 'visibilityState', {
            configurable: true, value: 'hidden',
          })
          fireEvent(document, new Event('visibilitychange'))
          expect(getAnalytics).toHaveBeenCalledTimes(1)

          Object.defineProperty(document, 'visibilityState', {
            configurable: true, value: 'visible',
          })
          fireEvent(document, new Event('visibilitychange'))
          fireEvent(window, new Event('focus'))
        }

        await waitFor(() => {
          expect(getAnalytics).toHaveBeenCalledTimes(2)
        })
        expect(getAnalytics).toHaveBeenNthCalledWith(2, query)

        // Обновление действительно ожидает новый ответ.
        expect(screen.getByRole('heading', {
          name: 'Loading analytics',
        })).toBeVisible()
        expect(within(heartCard).queryByText('72')).not.toBeInTheDocument()

        await act(async () => {
          resolveRefresh(updated)
        })

        await waitFor(() => {
          const currentCard = within(screen.getByRole('region', {
            name: 'Heart rate analytics',
          }))
          expect(currentCard.getByText('81')).toBeVisible()
          expect(currentCard.queryByText('72')).not.toBeInTheDocument()
        })
        expect(screen.queryByRole('heading', {
          name: 'Loading analytics',
        })).not.toBeInTheDocument()
        expect(screen.getByLabelText('Period')).toHaveValue('days_21')
        expect(screen.getByLabelText('Category')).toHaveValue('wellbeing')
        expect(router.state.location.search).toBe(
          '?period=days_21&checkin_category=wellbeing',
        )
        expect(getAnalytics).toHaveBeenCalledTimes(2)
      } finally {
        view.unmount()
        router.dispose()
        if (visibilityDescriptor) {
          Object.defineProperty(document, 'visibilityState', visibilityDescriptor)
        } else {
          Reflect.deleteProperty(document, 'visibilityState')
        }
      }
    },
  )

})

function RefreshProbe() {
  const [count, setCount] = useState(0)

  useRefreshSubscription(() => {
    setCount((value) => value + 1)
  })

  return <p>Refresh events: {count}</p>
}

function RequestRefreshButton() {
  const { requestRefresh } = useRefresh()

  return (
    <button type="button" onClick={() => requestRefresh()}>
      Refresh
    </button>
  )
}

function RefreshCallback({ onRefresh }: { onRefresh: () => void }) {
  useRefreshSubscription(onRefresh)
  return null
}

function MutationRefreshButton() {
  const { requestRefresh } = useRefresh()
  return <button type="button" onClick={() => requestRefresh('mutation')}>Mutation refresh</button>
}

function renderRoute(path: string, withMutationButton = false) {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [path],
  })

  return render(
    <AuthProvider>
      <AuthGate>
        <RefreshProvider>
          {withMutationButton && <MutationRefreshButton />}
          <RouterProvider router={router} />
        </RefreshProvider>
      </AuthGate>
    </AuthProvider>,
  )
}
