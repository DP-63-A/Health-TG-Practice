import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { entriesApi } from '../api/entries'
import type { Entry, EntryListResponse } from '../api/types'
import { AuthProvider } from '../auth/AuthProvider'
import { RefreshProvider } from '../refresh/RefreshProvider'
import { appRoutes } from '../router/router'

describe('FE1-03 diary', () => {
  it.each([
    ['sleep_quality', 'Sleep quality'],
    ['digestion_comfort', 'Digestive comfort'],
    ['wellbeing', 'Wellbeing'],
    ['mood', 'Mood'],
  ] as const)('shows a readable label for %s without field provenance', async (category, label) => {
    vi.spyOn(entriesApi, 'list').mockResolvedValue({
      items: [entryFixture({ type: 'checkin', payload: { category, score: 4 }, field_origins: { mass_g: 'reported', score: 'reported' } })],
      next_cursor: null,
    })
    await renderRoute('/diary')
    expect(await screen.findByText(`${label}: 4/5`)).toBeInTheDocument()
    expect(screen.queryByText(/mass_g|score: reported/)).not.toBeInTheDocument()
  })

  it.each(['heart_rate', 'sleep_duration_min'] as const)('shows %s measurement day independently of report day', async (code) => {
    vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [entryFixture({ type: 'metrics',
      occurred_at: '2026-10-07T23:50:00Z',
      payload: { code, value: 70, unit: code === 'heart_rate' ? 'bpm' : 'min', local_date: '2026-10-05' } })], next_cursor: null })
    await renderRoute('/diary?type=metrics')
    expect(await screen.findByText(`${code === 'heart_rate' ? 'Measurement date' : 'Wake date'}: 2026-10-05`)).toBeInTheDocument()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('names every filter and pagination action with native labels and text', async () => {
    vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [entryFixture({})], next_cursor: 'next' })
    await renderRoute('/diary')
    await screen.findByText('Test meal')
    const filters = within(screen.getByRole('form', { name: 'Diary filters' }))

    for (const name of ['Mode', 'From date', 'To date', 'Type']) {
      const control = filters.getByLabelText(name)
      expect(control).toHaveAccessibleName(name)
      expect(control.id).toBeTruthy()
      expect(document.querySelector(`label[for="${control.id}"]`)).toHaveTextContent(name)
    }
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled()
  })

  it('keeps sorting collapsed until opened and preserves filters when closed', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    await renderRoute('/diary', false)

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    const sorting = screen.getByRole('button', { name: 'Diary filters' })
    expect(screen.queryByText('Сортировка')).not.toBeInTheDocument()
    expect(sorting).toHaveAttribute('aria-expanded', 'false')
    const type = screen.getByLabelText('Type')
    expect(type).not.toBeVisible()

    const chevron = sorting.querySelector('.diary-title-chevron')
    expect(chevron).not.toBeNull()
    fireEvent.click(chevron!)
    expect(sorting).toHaveAttribute('aria-expanded', 'true')
    expect(type).toBeVisible()
    fireEvent.change(type, { target: { value: 'meal' } })
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({
      status: 'confirmed', limit: 2, cursor: undefined, type: 'meal',
    }, expect.any(AbortSignal)))
    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    const callsBeforeClosing = list.mock.calls.length

    fireEvent.click(sorting)
    expect(type).not.toBeVisible()
    fireEvent.click(sorting)
    expect(type).toBeVisible()
    expect(type).toHaveValue('meal')
    expect(list).toHaveBeenCalledTimes(callsBeforeClosing)
  })

  it('uses all Overview URL filters in the first request and shows the returned entry', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({
      items: [entryFixture({ payload: { description: 'September meal' }, occurred_at: '2026-09-03T09:00:00Z' })],
      next_cursor: null,
    })
    await renderRoute('/diary?from=2026-09-01&to=2026-09-07&type=meal')

    expect(await screen.findByText('September meal')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined,
      from: '2026-09-01', to: '2026-09-07', type: 'meal',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('From date')).toHaveValue('2026-09-01')
    expect(screen.getByLabelText('To date')).toHaveValue('2026-09-07')
    expect(screen.getByLabelText('Type')).toHaveValue('meal')
    expect(screen.getByLabelText('Mode')).toHaveValue('confirmed')
  })

  it.each(['meal', 'metrics', 'checkin'] as const)('accepts the Overview %s type without dates', async (type) => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [], next_cursor: null })
    await renderRoute(`/diary?type=${type}`)

    expect(await screen.findByText('No entries')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined, type,
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Type')).toHaveValue(type)
    expect(screen.getByLabelText('From date')).toHaveValue('')
    expect(screen.getByLabelText('To date')).toHaveValue('')
  })

  it('accepts partial date filters without a type', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [], next_cursor: null })
    await renderRoute('/diary?from=2026-09-01&to=2026-09-07')

    expect(await screen.findByText('No entries')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined,
      from: '2026-09-01', to: '2026-09-07',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Type')).toHaveValue('')
  })

  it.each(['unsupported', 'note', 'toString'] as const)('ignores unsupported URL type %s', async (type) => {
    const list = vi.spyOn(entriesApi, 'list')
    await renderRoute(`/diary?type=${type}`)

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, { status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Type')).toHaveValue('')
  })

  it('ignores malformed URL dates instead of inventing current dates', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    await renderRoute('/diary?from=not-a-date&to=2026-02-30&type=meal')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenNthCalledWith(1, {
      status: 'confirmed', limit: 2, cursor: undefined, type: 'meal',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('From date')).toHaveValue('')
    expect(screen.getByLabelText('To date')).toHaveValue('')
  })

  it.each(['2026-02-30', '2026-13-01', '2026-9-1'])(
    'rejects impossible or non-canonical URL date %s', async (date) => {
      const list = vi.spyOn(entriesApi, 'list')
      await renderRoute(`/diary?from=${date}`)

      expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
      expect(list).toHaveBeenCalledTimes(1)
      expect(list).toHaveBeenNthCalledWith(1, { status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
      expect(screen.getByLabelText('From date')).toHaveValue('')
    },
  )

  it('applies a new query when navigating to the already mounted Diary route', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockResolvedValue({ items: [], next_cursor: null })
    const { router } = await renderRoute('/diary?type=meal')
    expect(await screen.findByText('No entries')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(1)

    await act(async () => { await router.navigate('/diary?from=2026-09-01&to=2026-09-07&type=metrics') })

    fireEvent.click(await screen.findByRole('button', { name: 'Diary filters' }))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(list).toHaveBeenLastCalledWith({
      status: 'confirmed', limit: 2, cursor: undefined,
      from: '2026-09-01', to: '2026-09-07', type: 'metrics',
    }, expect.any(AbortSignal))
    expect(screen.getByLabelText('From date')).toHaveValue('2026-09-01')
    expect(screen.getByLabelText('To date')).toHaveValue('2026-09-07')
    expect(screen.getByLabelText('Type')).toHaveValue('metrics')
  })

  it('requests confirmed entries by default and keeps drafts in review mode', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    await renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenCalledWith({ status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    expect(screen.queryByText('Паста')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Mode'), { target: { value: 'draft' } })

    expect(await screen.findByText('Паста')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'draft', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()
  })

  it('applies from/to/type filters through a new server page request', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    await renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-09-18' } })

    expect(await screen.findByText('Mood: 4/5')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, from: '2026-09-18' }, expect.any(AbortSignal))
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'note' } })

    expect((await screen.findAllByText('После завтрака чувствую себя хорошо')).length).toBeGreaterThan(0)
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, from: '2026-09-18', type: 'note' }, expect.any(AbortSignal))
    expect(screen.queryByText('Mood: 4/5')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('To date'), { target: { value: '2026-09-18' } })

    expect(await screen.findByText('No entries')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, from: '2026-09-18', to: '2026-09-18', type: 'note' }, expect.any(AbortSignal))
  })

  it('switches pages without mixing old items and resets pagination on filter change', async () => {
    const list = vi.spyOn(entriesApi, 'list')
    await renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Mood: 4/5')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: '2' }, expect.any(AbortSignal))
    expect(screen.queryByText('Овсянка с ягодами')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'meal' } })

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, type: 'meal' }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Current page')).toHaveTextContent('1')
    expect(screen.queryByText('Mood: 4/5')).not.toBeInTheDocument()
  })

  it('uses the opaque cursor returned by the server and drops it after a filter change', async () => {
    const list = vi.spyOn(entriesApi, 'list').mockImplementation(async (filters): Promise<EntryListResponse> => {
      if (filters?.cursor === 'opaque:next-page') {
        return { items: [entryFixture({ id: 'second-page', type: 'note', payload: { text: 'Server second page' } })], next_cursor: null }
      }
      return { items: [entryFixture({ id: 'first-page', payload: { description: 'Server first page' } })], next_cursor: 'opaque:next-page' }
    })
    await renderRoute('/diary')

    expect(await screen.findByText('Server first page')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
    expect(screen.getByLabelText('Current page')).toHaveTextContent('1')
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined }, expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('link', { name: /Server second page/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByLabelText('Current page')).toHaveTextContent('2')
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: 'opaque:next-page' }, expect.any(AbortSignal))
    expect(screen.queryByText('Server first page')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'meal' } })
    expect(await screen.findByText('Server first page')).toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined, type: 'meal' }, expect.any(AbortSignal))
    expect(screen.getByLabelText('Current page')).toHaveTextContent('1')
    expect(screen.queryByRole('link', { name: /Server second page/ })).not.toBeInTheDocument()
  })

  it('shows empty state for an empty server result', async () => {
    await renderRoute('/diary')

    expect(await screen.findByText('Овсянка с ягодами')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-09-20' } })

    expect(await screen.findByText('No entries')).toBeInTheDocument()
  })

  it('opens a record and makes its protected source image available', async () => {
    await renderRoute('/diary/22222222-2222-4222-8222-222222222201')

    expect(await screen.findByRole('form', { name: 'Редактирование записи' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Источник записи' })).toBeInTheDocument()
    const imageLink = await screen.findByRole('link', { name: 'Открыть исходное изображение' })
    expect(imageLink).toHaveAttribute('href', 'blob:fixture/33333333-3333-4333-8333-333333333301')
    expect(imageLink.getAttribute('href')).not.toMatch(/token|bearer|session_token|fixture-session/i)
  })

  it('ignores stale list responses after a quick filter change', async () => {
    const responses: Array<(value: EntryListResponse) => void> = []
    vi.spyOn(entriesApi, 'list').mockImplementation(() => new Promise<EntryListResponse>((resolve) => { responses.push(resolve) }))

    await renderRoute('/diary')

    await waitFor(() => expect(responses).toHaveLength(1))
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'note' } })
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
    await renderRoute('/diary')

    await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('heading', { name: 'Loading entries' })).toBeInTheDocument()
    expect(screen.queryByText('No entries')).not.toBeInTheDocument()
    await act(async () => resolveList({ items: [], next_cursor: null }))
    expect(await screen.findByText('No entries')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Loading entries' })).not.toBeInTheDocument()
  })

  it('shows a failed list request and retries the same filters', async () => {
    const list = vi.spyOn(entriesApi, 'list')
      .mockRejectedValueOnce(new Error('Connection lost'))
      .mockResolvedValueOnce({ items: [entryFixture({ payload: { description: 'Recovered entry' } })], next_cursor: null })
    await renderRoute('/diary')

    expect(await screen.findByText('Connection lost')).toBeInTheDocument()
    expect(screen.queryByText('No entries')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Recovered entry')).toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(2)
    expect(list).toHaveBeenLastCalledWith({ status: 'confirmed', limit: 2, cursor: undefined }, undefined)
  })

  it('keeps the newest response when a refresh overtakes an older list request', async () => {
    const responses: Array<(value: EntryListResponse) => void> = []
    const list = vi.spyOn(entriesApi, 'list').mockImplementation(() => new Promise<EntryListResponse>((resolve) => { responses.push(resolve) }))
    await renderRoute('/diary')

    await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
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
      const view = await renderRoute('/diary/22222222-2222-4222-8222-222222222201')

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

async function renderRoute(path: string, openSorting = true) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  const view = render(<AuthProvider><RefreshProvider><RouterProvider router={router} /></RefreshProvider></AuthProvider>)
  if (openSorting && /^\/diary(?:\?|$)/.test(path)) {
    fireEvent.click(await screen.findByRole('button', { name: 'Diary filters' }))
  }
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
