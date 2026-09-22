import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { appRoutes } from '../router/router'
import { RefreshProvider, useRefresh, useRefreshSubscription } from './RefreshProvider'

describe('refresh mechanism', () => {
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
    renderRoute('/diary')

    expect(await screen.findByText('Обновлений: 0')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))

    expect(screen.getByText('Обновлений: 1')).toBeInTheDocument()
  })

  it('OverviewPage receives refresh event', async () => {
    renderRoute('/overview')

    expect(await screen.findByText('Обновлений: 0')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))

    expect(screen.getByText('Обновлений: 1')).toBeInTheDocument()
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
    <button type="button" onClick={requestRefresh}>
      Обновить
    </button>
  )
}

function renderRoute(path: string) {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [path],
  })

  return render(
    <RefreshProvider>
      <RouterProvider router={router} />
    </RefreshProvider>,
  )
}
