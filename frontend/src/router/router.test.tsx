import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { RefreshProvider } from '../refresh/RefreshProvider'
import { AuthProvider } from '../auth/AuthProvider'
import { appRoutes } from './router'

describe('app routes', () => {
  it('renders /diary route', async () => {
    renderRoute('/diary')

    expect(
      await screen.findByRole('region', { name: 'Diary' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Овсянка с ягодами/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Diary', level: 2 })).not.toBeInTheDocument()
    expect(screen.queryByText('Health TG Practice')).not.toBeInTheDocument()
    expect(screen.queryByText('Режим: fixture')).not.toBeInTheDocument()
    expect(screen.queryByText('История confirmed и отдельный режим проверки draft.')).not.toBeInTheDocument()
    expect(screen.queryByText(/revision/)).not.toBeInTheDocument()
  })

  it('renders /overview route', async () => {
    renderRoute('/overview')

    expect(
      await screen.findByRole('heading', { name: 'Stats', level: 1 }),
    ).toBeInTheDocument()
  })
})

function renderRoute(path: string) {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [path],
  })

  return render(
    <AuthProvider><RefreshProvider><RouterProvider router={router} /></RefreshProvider></AuthProvider>,
  )
}
