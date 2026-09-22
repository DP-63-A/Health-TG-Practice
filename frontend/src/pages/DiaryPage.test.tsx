import { fireEvent, render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import { RefreshProvider } from '../refresh/RefreshProvider'
import { appRoutes } from '../router/router'

describe('FE-1 diary', () => {
  it('lists confirmed entries and filters drafts', async () => {
    renderRoute('/diary')
    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Статус'), { target: { value: 'draft' } })
    expect(await screen.findByText('Паста')).toBeInTheDocument()
  })

  it('opens a record with revision and source link', async () => {
    renderRoute('/diary/22222222-2222-4222-8222-222222222201')
    expect(await screen.findByRole('heading', { name: 'Проверка записи' })).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Открыть исходное изображение' })).toHaveAttribute('href', '/api/v1/files/33333333-3333-4333-8333-333333333301')
  })
})

function renderRoute(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return render(<AuthProvider><RefreshProvider><RouterProvider router={router} /></RefreshProvider></AuthProvider>)
}
