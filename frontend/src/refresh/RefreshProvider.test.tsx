import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode, useState } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { entriesApi } from '../api/entries'
import { analyticsFixture } from '../overview/fixtures/analytics.fixture'
import * as analytics from '../overview/analytics'
import { appRoutes } from '../router/router'
import { AuthProvider } from '../auth/AuthProvider'
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

    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))

    expect(screen.getByText('Refresh events: 1')).toBeInTheDocument()
  })

  it('DiaryPage receives refresh event', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))

    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
  })

  it('OverviewPage receives refresh event', async () => {
    const getAnalytics = vi.spyOn(analytics, 'getAnalytics').mockResolvedValue(analyticsFixture)
    renderRoute('/overview')

    expect(await screen.findByText('Обновлений: 0')).toBeInTheDocument()
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))

    expect(screen.getByText('Обновлений: 1')).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))
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
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
      fireEvent(window, new Event('pagehide'))
      fireEvent(document, new Event('visibilitychange'))
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
      fireEvent(window, new Event('focus'))
      fireEvent(document, new Event('visibilitychange'))
      fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }))
      await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
      fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }))
      await new Promise((resolve) => setTimeout(resolve, 160))
      expect(list).toHaveBeenCalledTimes(2)
    } finally {
      if (descriptor) Object.defineProperty(document, 'visibilityState', descriptor)
      else Reflect.deleteProperty(document, 'visibilityState')
    }
  })

  it('cleans lifecycle listeners and pending resume on unmount', async () => {
    const onRefresh = vi.fn()
    const view = render(<StrictMode><RefreshProvider><RefreshCallback onRefresh={onRefresh} /></RefreshProvider></StrictMode>)
    fireEvent(window, new Event('blur'))
    fireEvent(window, new Event('focus'))
    view.unmount()
    await new Promise((resolve) => setTimeout(resolve, 160))
    fireEvent(window, new Event('focus'))
    expect(onRefresh).not.toHaveBeenCalled()
  })

  it('ignores an older Overview response after a newer shared refresh', async () => {
    let resolveOld: (value: typeof analyticsFixture) => void = () => undefined
    let resolveNew: (value: typeof analyticsFixture) => void = () => undefined
    const getAnalytics = vi.spyOn(analytics, 'getAnalytics')
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve }))
    renderRoute('/overview')
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))
    await waitFor(() => expect(getAnalytics).toHaveBeenCalledTimes(2))
    resolveNew({ ...analyticsFixture, cards: { ...analyticsFixture.cards, meal_count: { count: 9 } } })
    const card = await screen.findByRole('region', { name: 'Количество приёмов пищи' })
    expect(within(card).getByText('9')).toBeInTheDocument()
    resolveOld(analyticsFixture)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(within(card).getByText('9')).toBeInTheDocument()
    expect(within(card).queryByText('2')).not.toBeInTheDocument()
  })
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
      Обновить
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
    <AuthProvider><RefreshProvider>{withMutationButton && <MutationRefreshButton />}<RouterProvider router={router} /></RefreshProvider></AuthProvider>,
  )
}
