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

  it('uses all Overview URL filters in the first request and shows the returned entry', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({
      items: [entryFixture({ payload: { description: 'September meal' }, occurred_at: '2026-09-03T09:00:00Z' })],
      next_cursor: null,
    })
    renderRoute('/diary?from=2026-09-01&to=2026-09-07&type=meal')

    expect(await screen.findByText('September meal')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined,
      from: '2026-09-01', to: '2026-09-07', type: 'meal',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('С даты')).toHaveValue('2026-09-01')
    expect(screen.getByLabelText('По дату')).toHaveValue('2026-09-07')
    expect(screen.getByLabelText('Тип')).toHaveValue('meal')
    expect(screen.getByLabelText('Режим')).toHaveValue('confirmed')
  })

  it.each(['meal', 'metrics', 'checkin'] as const)('accepts the Overview %s type without dates', async (type) => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [], next_cursor: null })
    renderRoute(`/diary?type=${type}`)

    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined, type,
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Тип')).toHaveValue(type)
    expect(screen.getByLabelText('С даты')).toHaveValue('')
    expect(screen.getByLabelText('По дату')).toHaveValue('')
  })

  it('accepts partial date filters without a type', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [], next_cursor: null })
    renderRoute('/diary?from=2026-09-01&to=2026-09-07')

    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined,
      from: '2026-09-01', to: '2026-09-07',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Тип')).toHaveValue('')
  })

  it.each(['unsupported', 'note', 'toString'] as const)('ignores unsupported URL type %s', async (type) => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute(`/diary?type=${type}`)

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, { status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Тип')).toHaveValue('')
  })

  it('ignores malformed URL dates instead of inventing current dates', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary?from=not-a-date&to=2026-02-30&type=meal')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined, type: 'meal',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('С даты')).toHaveValue('')
    expect(screen.getByLabelText('По дату')).toHaveValue('')
  })

  it.each(['2026-02-30', '2026-13-01', '2026-9-1'])(
    'rejects impossible or non-canonical URL date %s', async (date) => {
      const list = vi.spyOn(entriesApi, 'list')
      renderRoute(`/diary?from=${date}`)

      expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
      expect(list).toHaveBeenCalledTimes(1)
      expect(list).toHaveBeenNthCalledWith(1, { status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
      expect(screen.getByLabelText('С даты')).toHaveValue('')
    },
  )

  it('applies a new query when navigating to the already mounted Diary route', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [], next_cursor: null })
    const { router } = renderRoute('/diary?type=meal')
    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)

    await act(async () => { await router.navigate('/diary?from=2026-09-01&to=2026-09-07&type=metrics') })

    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(list).toHaveBeenLastCalledWith({
      status: 'confirmed', limit: 2, cursor: undefined,
      from: '2026-09-01', to: '2026-09-07', type: 'metrics',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('С даты')).toHaveValue('2026-09-01')
    expect(screen.getByLabelText('По дату')).toHaveValue('2026-09-07')
    expect(screen.getByLabelText('Тип')).toHaveValue('metrics')
  })

  it('requests confirmed entries by default and keeps drafts in review mode', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledWith({ status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    expect(screen.queryByText('Паста')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Режим'), { target: { value: 'draft' } })

    expect(await screen.findByText('Паста')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'draft', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()
  })

  it('applies from/to/type filters through a new server page request', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('С даты'), { target: { value: '2026-09-18' } })

    expect(await screen.findByText('mood: 4/5')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, from: '2026-09-18' }, expect.any(AbortSignal))
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Тип'), { target: { value: 'note' } })

    expect((await screen.findAllByText('После завтрака чувствую себя хорошо')).length).toBeGreaterThan(0)
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, from: '2026-09-18', type: 'note' }, expect.any(AbortSignal))
    expect(screen.queryByText('mood: 4/5')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('По дату'), { target: { value: '2026-09-18' } })

    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, from: '2026-09-18', to: '2026-09-18', type: 'note' }, expect.any(AbortSignal))
  })

  it('switches pages without mixing old items and resets pagination on filter change', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }))

    expect(await screen.findByText('mood: 4/5')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: '2' }, expect.any(AbortSignal))
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Тип'), { target: { value: 'meal' } })

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, type: 'meal' }, expect.any(AbortSignal))
    expect(screen.getByText('Страница 1')).toBeInTheDocument()
    expect(screen.queryByText('mood: 4/5')).not.toBeInTheDocument()
  })

  it('uses the opaque cursor returned by the server and drops it after a filter change', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockImplementation(async (filters): Promise<EntryListResponse> => {
      if (filters?.cursor === 'opaque:next-page') {
        return { items: [entryFixture({ id: 'second-page', type: 'note', payload: { text: 'Server second page' } })], next_cursor: null }
      }
      return { items: [entryFixture({ id: 'first-page', payload: { description: 'Server first page' } })], next_cursor: 'opaque:next-page' }
    })
    renderRoute('/diary')

    expect(await screen.findByText('Server first page')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }))
    expect(await screen.findByRole('link', { name: /Server second page/ })).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: 'opaque:next-page' }, expect.any(AbortSignal))
    expect(screen.queryByText('Server first page')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Тип'), { target: { value: 'meal' } })
    expect(await screen.findByText('Server first page')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, type: 'meal' }, expect.any(AbortSignal))
    expect(screen.getByText('Страница 1')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Server second page/ })).not.toBeInTheDocument()
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

  it('keeps loading distinct from empty until the API resolves', async () => {
    let resolveList: (value: EntryListResponse) => void = () => undefined
    const list = vi.spyOn(entriesApi, 'list').mockImplementation(() => new Promise<EntryListResponse>((resolve) => { resolveList = resolve }))
    renderRoute('/diary')

    await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('heading', { name: 'Загрузка записей' })).toBeInTheDocument()
    expect(screen.queryByText('Записей нет')).not.toBeInTheDocument()
    await act(async () => resolveList({ items: [], next_cursor: null }))
    expect(await screen.findByText('Записей нет')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Загрузка записей' })).not.toBeInTheDocument()
  })

  it('shows a failed list request and retries the same filters', async () => {
    const list = vi.spyOn(entriesApi, 'list')
      .mockRejectedValueOnce(new Error('Connection lost'))
      .mockResolvedValueOnce({ items: [entryFixture({ payload: { description: 'Recovered entry' } })], next_cursor: null })
    renderRoute('/diary')

    expect(await screen.findByText('Connection lost')).toBeInTheDocument()
    expect(screen.queryByText('Записей нет')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(await screen.findByText('Recovered entry')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(2)
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined }, undefined)
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
  const view = render(<AuthProvider><RefreshProvider><RouterProvider router={router} /></RefreshProvider></AuthProvider>)
  return { ...view, router }
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
