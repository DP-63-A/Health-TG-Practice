import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { entriesApi } from '../api/entries'
import type { Entry, EntryListResponse } from '../api/types'
import { AuthProvider } from '../auth/AuthProvider'
import { RefreshProvider } from '../refresh/RefreshProvider'
import { appRoutes } from '../router/router'

describe('FE1-03 diary', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('requests confirmed entries by default and keeps drafts in review mode', async () => {
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(screen.queryByText('Паста')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Режим'), { target: { value: 'draft' } })

    expect(await screen.findByText('Паста')).toBeInTheDocument()
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()
  })

  it('applies from/to/type filters through a new server page request', async () => {
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('С даты'), { target: { value: '2026-09-18' } })

    expect(await screen.findByText('mood: 4/5')).toBeInTheDocument()
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Тип'), { target: { value: 'note' } })

    expect((await screen.findAllByText('После завтрака чувствую себя хорошо')).length).toBeGreaterThan(0)
    expect(screen.queryByText('mood: 4/5')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('По дату'), { target: { value: '2026-09-18' } })

    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
  })

  it('switches pages without mixing old items and resets pagination on filter change', async () => {
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }))

    expect(await screen.findByText('mood: 4/5')).toBeInTheDocument()
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Тип'), { target: { value: 'meal' } })

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(screen.getByText('Страница 1')).toBeInTheDocument()
    expect(screen.queryByText('mood: 4/5')).not.toBeInTheDocument()
  })

  it('shows empty state for an empty server result', async () => {
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('С даты'), { target: { value: '2026-09-20' } })

    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
  })

  it('opens a record with revision, source and protected file object URL', async () => {
    renderRoute('/diary/22222222-2222-4222-8222-222222222201')

    expect(await screen.findByRole('heading', { name: 'Проверка записи' })).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Источник записи' })).toBeInTheDocument()
    const link = await screen.findByRole('link', { name: 'Открыть исходное изображение' })
    expect(link).toHaveAttribute('href', 'blob:fixture/33333333-3333-4333-8333-333333333301')
    expect(link.getAttribute('href')).not.toMatch(/token|bearer|session_token|fixture-session/i)
  })

  it('ignores stale list responses after a quick filter change', async () => {
    const responses: Array<(value: EntryListResponse) => void> = []
    vi.spyOn(entriesApi, 'list').mockImplementation(() => new Promise<EntryListResponse>((resolve) => { responses.push(resolve) }))

    renderRoute('/diary')

    await waitFor(() => expect(responses).toHaveLength(1))
    fireEvent.change(screen.getByLabelText('Тип'), { target: { value: 'note' } })
    await waitFor(() => expect(responses).toHaveLength(2))

    await act(async () => {
      responses[1]({ items: [entryFixture({ id: 'fresh-note', type: 'note', payload: { text: 'Fresh async note' } })], next_cursor: null })
    })

    expect((await screen.findAllByText('Fresh async note')).length).toBeGreaterThan(0)

    await act(async () => {
      responses[0]({ items: [entryFixture({ id: 'old-meal', type: 'meal', payload: { description: 'Old async meal' } })], next_cursor: null })
    })

    expect(screen.getAllByText('Fresh async note').length).toBeGreaterThan(0)
    expect(screen.queryByText('Old async meal')).not.toBeInTheDocument()
  })

  it('keeps the newest response when a refresh overtakes an older list request', async () => {
    const responses: Array<(value: EntryListResponse) => void> = []
    const list = vi.spyOn(entriesApi, 'list').mockImplementation(() => new Promise<EntryListResponse>((resolve) => { responses.push(resolve) }))
    renderRoute('/diary')

    await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))

    await act(async () => responses[1]({ items: [entryFixture({ id: 'fresh', payload: { description: 'Fresh after refresh' } })], next_cursor: null }))
    expect(await screen.findByText('Fresh after refresh')).toBeInTheDocument()
    await act(async () => responses[0]({ items: [entryFixture({ id: 'stale', payload: { description: 'Stale before refresh' } })], next_cursor: null }))
    expect(screen.getByText('Fresh after refresh')).toBeInTheDocument()
    expect(screen.queryByText('Stale before refresh')).not.toBeInTheDocument()
  })

  it('revokes protected file object URL if file loading finishes after unmount', async () => {
    const originalRevokeObjectURL = URL.revokeObjectURL
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL })
    let resolveFile: ((url: string) => void) | undefined
    vi.spyOn(entriesApi, 'downloadFile').mockImplementation(() => new Promise<string>((resolve) => { resolveFile = resolve }))

    try {
      const view = renderRoute('/diary/22222222-2222-4222-8222-222222222201')

      await waitFor(() => expect(resolveFile).toBeDefined())
      view.unmount()

      await act(async () => {
        resolveFile?.('blob:late-protected-file')
      })

      expect(revokeObjectURL).toHaveBeenCalledWith('blob:late-protected-file')
    } finally {
      Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: originalRevokeObjectURL })
    }
  })
})

function renderRoute(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return render(<AuthProvider><RefreshProvider><RouterProvider router={router} /></RefreshProvider></AuthProvider>)
}

function entryFixture(overrides: Partial<Entry>): Entry {
  return {
    id: 'entry',
    user_id: '11111111-1111-4111-8111-111111111101',
    type: 'meal',
    status: 'confirmed',
    source_kind: 'text',
    source_ref: { label: 'test source' },
    occurred_at: '2026-09-18T09:00:00Z',
    created_at: '2026-09-18T09:00:00Z',
    updated_at: '2026-09-18T09:00:00Z',
    revision: 1,
    payload: { description: 'Test meal' },
    field_origins: {},
    submission_id: 'test-submission',
    ...overrides,
  }
}
