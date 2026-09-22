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
      await screen.findByRole('heading', { name: 'Дневник', level: 2 }),
    ).toBeInTheDocument()
    expect(screen.getByText('Режим: fixture')).toBeInTheDocument()
  })

  it('renders /overview route', async () => {
    renderRoute('/overview')

    expect(
      await screen.findByRole('heading', { name: 'Обзор', level: 2 }),
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
