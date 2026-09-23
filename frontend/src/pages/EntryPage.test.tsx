import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type { Entry, EntryPayload, EntryType } from '../api/types'
import { AuthProvider } from '../auth/AuthProvider'
import { RefreshProvider, useRefreshSubscription } from '../refresh/RefreshProvider'
import { appRoutes } from '../router/router'

describe('FE1-04 entry review and correction', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads a draft by id and shows contract fields, source, unknown and origin data', async () => {
    const entry = entryFixture({
      status: 'draft',
      payload: {
        description: 'Pasta',
        mass_g: null,
        nutrients: { energy_kcal: 450, protein_g: null, fat_g: null, carbs_g: null },
        nutrients_basis: 'per_serving',
      },
      field_origins: { description: 'estimated', mass_g: 'estimated', 'nutrients.energy_kcal': 'estimated' },
      submission_id: null,
    })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    expect(await screen.findByRole('heading', { name: 'Проверка записи' })).toBeInTheDocument()
    expect(entriesApi.get).toHaveBeenCalledWith(entry.id, expect.any(AbortSignal))
    expect(screen.getByLabelText(/Описание/)).toHaveValue('Pasta')
    expect(screen.getByLabelText(/Масса/)).toHaveValue('')
    expect(screen.getAllByText('Неизвестно').length).toBeGreaterThan(0)
    expect(screen.getByRole('region', { name: 'Происхождение полей' })).toHaveTextContent('estimated')
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Изменить' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Подтвердить' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Не сохранять' })).toBeInTheDocument()
  })

  it('loads a confirmed entry by id and edits it through PATCH with expected_revision', async () => {
    const entry = entryFixture({
      status: 'confirmed',
      revision: 5,
      payload: { description: 'Oatmeal', mass_g: 200, nutrients: { energy_kcal: 330 }, nutrients_basis: 'per_100g' },
      submission_id: 'sub_confirmed',
    })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue(entryFixture({
      ...entry,
      revision: 6,
      payload: { description: 'Oatmeal', mass_g: 150, nutrients: { energy_kcal: 330 }, nutrients_basis: 'per_100g' },
    }))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Масса/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch).toHaveBeenCalledWith(entry.id, expect.objectContaining({
      expected_revision: 5,
      payload: expect.objectContaining({ mass_g: 150 }),
    }))
    expect(screen.queryByRole('button', { name: 'Изменить' })).not.toBeInTheDocument()
    expect(await screen.findByText('Изменения сохранены.')).toBeInTheDocument()
  })

  it.each([
    ['meal', { description: 'Meal', mass_g: null, nutrients: { energy_kcal: null }, nutrients_basis: 'unknown' }, [/Описание/, /Масса/, /Ккал/]],
    ['metrics', { code: 'steps', value: 0, unit: null, local_date: null, local_time: null, qualifier: null }, [/Показатель/, /Значение/, /Единица/, /Локальная дата/]],
    ['checkin', { category: 'mood', score: 4 }, [/Категория/, /Оценка/]],
    ['note', { text: 'Plain note' }, [/Текст/]],
  ] satisfies Array<[EntryType, EntryPayload, RegExp[]]>)('builds the %s form from contract fields', async (type, payload, labels) => {
    const entry = entryFixture({ type, payload })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    expect(await screen.findByRole('group', { name: type === 'meal' ? 'Питание' : type === 'metrics' ? 'Метрика' : type === 'checkin' ? 'Оценка' : 'Заметка' })).toBeInTheDocument()
    for (const label of labels) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
  })

  it('confirms a draft with expected_revision and a stable submission_id without duplicate double-click', async () => {
    const entry = entryFixture({ status: 'draft', revision: 2, submission_id: null })
    let resolveConfirm: (entry: Entry) => void = () => undefined
    const confirm = vi.spyOn(entriesApi, 'confirm').mockImplementation(() => new Promise<Entry>((resolve) => { resolveConfirm = resolve }))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    const button = await screen.findByRole('button', { name: 'Подтвердить' })
    fireEvent.click(button)
    fireEvent.click(button)

    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
    expect(confirm).toHaveBeenCalledWith(entry.id, {
      expected_revision: 2,
      submission_id: expect.stringMatching(new RegExp(`^web_${entry.id}_`)),
    })

    await act(async () => {
      resolveConfirm({ ...entry, status: 'confirmed', submission_id: 'server-submission', revision: 3 })
    })

    expect(await screen.findByText('Запись подтверждена.')).toBeInTheDocument()
  })

  it('saves changed draft fields before confirm and uses the updated revision', async () => {
    const entry = entryFixture({ status: 'draft', revision: 1, submission_id: null })
    const saved = entryFixture({ ...entry, revision: 2, payload: { description: 'Changed meal', mass_g: 100 } })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue(clone(saved))
    const confirm = vi.spyOn(entriesApi, 'confirm').mockResolvedValue({ ...saved, status: 'confirmed', submission_id: 'sub_web' })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Описание/), { target: { value: 'Changed meal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))

    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
    expect(patch).toHaveBeenCalledWith(entry.id, expect.objectContaining({ expected_revision: 1 }))
    expect(confirm).toHaveBeenCalledWith(entry.id, expect.objectContaining({ expected_revision: 2 }))
  })

  it('cancels a draft through the cancel endpoint and requests shared refresh', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    const refresh = vi.fn()
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const cancel = vi.spyOn(entriesApi, 'cancel').mockResolvedValue({ ...entry, status: 'cancelled' })

    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.click(await screen.findByRole('button', { name: 'Не сохранять' }))

    await waitFor(() => expect(cancel).toHaveBeenCalledWith(entry.id))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  })

  it('shows success only after the PATCH API resolves', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 3 })
    let resolvePatch: (entry: Entry) => void = () => undefined
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    vi.spyOn(entriesApi, 'patch').mockImplementation(() => new Promise<Entry>((resolve) => { resolvePatch = resolve }))

    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Описание/), { target: { value: 'Waiting' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(screen.queryByText('Изменения сохранены.')).not.toBeInTheDocument()

    await act(async () => {
      resolvePatch({ ...entry, revision: 4, payload: { description: 'Waiting' } })
    })

    expect(await screen.findByText('Изменения сохранены.')).toBeInTheDocument()
  })

  it('keeps input and shows 422 field_errors next to fields without false success', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    vi.spyOn(entriesApi, 'patch').mockRejectedValue(new ApiError({
      code: 'VALIDATION_ERROR',
      message: 'Request body failed validation',
      request_id: 'req_422',
      field_errors: [{ field: 'payload.mass_g', message: 'mass must be >= 0' }],
    }, 422))

    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Масса/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('mass must be >= 0')).toBeInTheDocument()
    expect(screen.getByLabelText(/Масса/)).toHaveValue('150')
    expect(screen.queryByText('Изменения сохранены.')).not.toBeInTheDocument()
  })

  it('keeps input on network error without false success', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const refresh = vi.fn()
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    vi.spyOn(entriesApi, 'patch').mockRejectedValue(new Error('Network down'))

    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.change(await screen.findByLabelText(/Описание/), { target: { value: 'Local text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Network down')).toBeInTheDocument()
    expect(screen.getByLabelText(/Описание/)).toHaveValue('Local text')
    expect(screen.queryByText('Изменения сохранены.')).not.toBeInTheDocument()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('keeps local input on 409 and does not overwrite or resubmit with fresh revision', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 1, payload: { description: 'Local base', mass_g: 200 } })
    const fresh = entryFixture({ ...entry, revision: 2, payload: { description: 'Server text', mass_g: 175 } })
    const get = vi.spyOn(entriesApi, 'get')
      .mockResolvedValueOnce(clone(entry))
      .mockResolvedValue(clone(fresh))
    const patch = vi.spyOn(entriesApi, 'patch').mockRejectedValue(new ApiError({
      code: 'VERSION_CONFLICT',
      message: 'expected_revision 1 is stale',
      request_id: 'req_409',
    }, 409))
    const refresh = vi.fn()

    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.change(await screen.findByLabelText(/Описание/), { target: { value: 'My unsaved text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText(/Конфликт версий/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Описание/)).toHaveValue('My unsaved text')
    expect(await screen.findByRole('region', { name: 'Свежая серверная версия' })).toHaveTextContent('Server text')
    expect(patch).toHaveBeenCalledTimes(1)
    expect(patch.mock.calls[0][1].expected_revision).toBe(1)
    expect(get).toHaveBeenCalledTimes(2)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('does not replace an unknown occurred_at with the current time', async () => {
    const entry = entryFixture({ status: 'confirmed', occurred_at: null as unknown as string })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    expect(await screen.findByLabelText(/Дата и время/)).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).not.toHaveProperty('occurred_at')
  })

  it('converts manual date/time by the authenticated user timezone', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 7, occurred_at: '2026-09-16T12:00:00Z' })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 8 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    expect(await screen.findByLabelText(/Дата и время/)).toHaveValue('2026-09-16T14:00')
    fireEvent.change(screen.getByLabelText(/Дата и время/), { target: { value: '2026-09-16T15:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).toMatchObject({ occurred_at: '2026-09-16T13:00:00.000Z' })
  })

  it('requests shared refresh after successful PATCH and confirm', async () => {
    const entry = entryFixture({ status: 'draft', revision: 1, submission_id: null })
    const refresh = vi.fn()
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2 })
    vi.spyOn(entriesApi, 'confirm').mockResolvedValue({ ...entry, status: 'confirmed', revision: 3, submission_id: 'sub' })

    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.change(await screen.findByLabelText(/Масса/), { target: { value: '123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(2))
  })

  it('edits without confirming and PATCHes only the changed field', async () => {
    const entry = entryFixture({ status: 'draft', revision: 4, submission_id: null })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 5, payload: { ...entry.payload, mass_g: 150 } })
    const confirm = vi.spyOn(entriesApi, 'confirm')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Изменить' }))
    expect(document.activeElement).toBe(screen.getByLabelText(/Дата и время/))
    expect(confirm).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText(/Масса/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 4, payload: { mass_g: 150 } })
    expect(confirm).not.toHaveBeenCalled()
  })

  it('preserves omitted metrics fields and sends only the edited value', async () => {
    const entry = entryFixture({ type: 'metrics', status: 'draft', payload: { code: 'heart_rate', value: 80 } })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2, payload: { code: 'heart_rate', value: 75 } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Значение/), { target: { value: '75' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 1, payload: { value: 75 } })
  })

  it('shows every editable meal nutrient with its unit and preserves null separately from zero', async () => {
    const entry = entryFixture({ payload: {
      description: 'Meal', mass_g: 0,
      nutrients: { energy_kcal: null, protein_g: 1, fat_g: 2, carbs_g: 3 },
      nutrients_basis: 'per_100g',
    } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    expect(await screen.findByLabelText(/Масса/)).toHaveValue('0')
    expect(screen.getByLabelText(/Ккал/)).toHaveValue('')
    expect(screen.getByLabelText(/Белки/)).toHaveValue('1')
    expect(screen.getByLabelText(/Жиры/)).toHaveValue('2')
    expect(screen.getByLabelText(/Углеводы/)).toHaveValue('3')
    expect(screen.getByLabelText(/Основа нутриентов/)).toHaveValue('per_100g')
  })

  it('shows all optional metrics fields from the contract', async () => {
    const entry = entryFixture({ type: 'metrics', payload: {
      code: 'heart_rate', value: 80, unit: 'bpm', local_date: '2026-09-16',
      local_time: '08:35', qualifier: 'resting',
    } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    expect(await screen.findByLabelText(/Показатель/)).toHaveValue('heart_rate')
    expect(screen.getByLabelText(/Значение/)).toHaveValue('80')
    expect(screen.getByLabelText(/Единица/)).toHaveValue('bpm')
    expect(screen.getByLabelText(/Локальная дата/)).toHaveValue('2026-09-16')
    expect(screen.getByLabelText(/Локальное время/)).toHaveValue('08:35')
    expect(screen.getByLabelText(/Уточнение пульса/)).toHaveValue('resting')
  })

  it.each(['sleep_quality', 'digestion_comfort', 'wellbeing', 'mood'] as const)(
    'supports the %s assessment category', async (category) => {
      const entry = entryFixture({ type: 'checkin', payload: { category, score: 3 } })
      const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2, payload: { category, score: 4 } })
      vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
      renderRoute(`/diary/${entry.id}`)
      expect(await screen.findByLabelText(/Категория/)).toHaveValue(category)
      fireEvent.change(screen.getByLabelText(/Оценка/), { target: { value: '4' } })
      fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
      await waitFor(() => expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 1, payload: { score: 4 } }))
    },
  )

  it('blocks a rapid second PATCH and a conflicting cancel while saving', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    let rejectPatch: (reason: Error) => void = () => undefined
    const patch = vi.spyOn(entriesApi, 'patch').mockImplementation(() => new Promise<Entry>((_, reject) => { rejectPatch = reject }))
    const cancel = vi.spyOn(entriesApi, 'cancel')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Масса/), { target: { value: '150' } })
    const save = screen.getByRole('button', { name: 'Сохранить' })
    fireEvent.click(save)
    fireEvent.click(save)
    fireEvent.click(screen.getByRole('button', { name: 'Не сохранять' }))
    expect(patch).toHaveBeenCalledTimes(1)
    expect(cancel).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Масса/)).toHaveValue('150')
    await act(async () => rejectPatch(new Error('Network down')))
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeEnabled()
  })

  it('blocks a rapid second cancel and restores controls after a network error', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    let rejectCancel: (reason: Error) => void = () => undefined
    const cancel = vi.spyOn(entriesApi, 'cancel').mockImplementation(() => new Promise<Entry>((_, reject) => { rejectCancel = reject }))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    const button = await screen.findByRole('button', { name: 'Не сохранять' })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(cancel).toHaveBeenCalledTimes(1)
    await act(async () => rejectCancel(new Error('Network down')))
    expect(screen.getByRole('button', { name: 'Не сохранять' })).toBeEnabled()
  })

  it('maps a nested server field error and permits retry after correction', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch')
      .mockRejectedValueOnce(new ApiError({ code: 'VALIDATION_ERROR', message: 'Invalid data', request_id: 'req', field_errors: [
        { field: '/payload/nutrients/energy_kcal', message: 'Check energy' },
      ] }, 422))
      .mockResolvedValueOnce({ ...entry, revision: 2 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Ккал/), { target: { value: '400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    expect(await screen.findByText('Check energy')).toBeInTheDocument()
    expect(screen.getByLabelText(/Ккал/)).toHaveValue('400')
    fireEvent.change(screen.getByLabelText(/Ккал/), { target: { value: '390' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(2))
  })

  it('does not convert an unchanged date when saving another field', async () => {
    const entry = entryFixture({ occurred_at: '2026-09-16T12:00:37Z', status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Масса/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).not.toHaveProperty('occurred_at')
  })

  it('rejects a nonexistent DST local time without sending PATCH', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Дата и время/), { target: { value: '2026-03-29T02:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    expect(await screen.findByText('Дата или время не существует либо неоднозначно в часовом поясе пользователя.')).toBeInTheDocument()
    expect(patch).not.toHaveBeenCalled()
  })

  it('rejects an ambiguous DST local time rather than silently choosing an offset', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Дата и время/), { target: { value: '2026-10-25T02:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    expect(await screen.findByText('Дата или время не существует либо неоднозначно в часовом поясе пользователя.')).toBeInTheDocument()
    expect(patch).not.toHaveBeenCalled()
  })

  it('reuses submission_id after an uncertain network failure on retry', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    const confirm = vi.spyOn(entriesApi, 'confirm')
      .mockRejectedValueOnce(new Error('Network down'))
      .mockResolvedValueOnce({ ...entry, status: 'confirmed', revision: 2, submission_id: 'stored' })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }))
    expect(await screen.findByText('Network down')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(2))
    expect(confirm.mock.calls[1][1]).toEqual(confirm.mock.calls[0][1])
  })

  it('loads a protected source through the API and keeps its file URL out of the link', async () => {
    const entry = entryFixture({ source_ref: { file_id: '33333333-3333-4333-8333-333333333301', label: 'photo' } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const download = vi.spyOn(entriesApi, 'downloadFile').mockResolvedValue('blob:fixture/owned-image')
    renderRoute(`/diary/${entry.id}`)
    const link = await screen.findByRole('link', { name: 'Открыть исходное изображение' })
    expect(download).toHaveBeenCalledWith(entry.source_ref.file_id, expect.any(AbortSignal))
    expect(link).toHaveAttribute('href', 'blob:fixture/owned-image')
    expect(link).not.toHaveAttribute('href', expect.stringContaining('/files/'))
  })

  it('does not apply an old PATCH response after navigating to another entry ID', async () => {
    const first = entryFixture({ status: 'confirmed' })
    const second = entryFixture({ id: '55555555-5555-4555-8555-555555555555', status: 'confirmed', payload: { description: 'Second entry', mass_g: 50 } })
    let resolvePatch: (value: Entry) => void = () => undefined
    vi.spyOn(entriesApi, 'get').mockImplementation(async (id) => clone(id === first.id ? first : second))
    vi.spyOn(entriesApi, 'patch').mockImplementation(() => new Promise<Entry>((resolve) => { resolvePatch = resolve }))
    const { router } = renderRoute(`/diary/${first.id}`)

    fireEvent.change(await screen.findByLabelText(/Описание/), { target: { value: 'First changed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
    await act(async () => { await router.navigate(`/diary/${second.id}`) })
    expect(await screen.findByLabelText(/Описание/)).toHaveValue('Second entry')
    await act(async () => resolvePatch({ ...first, revision: 2, payload: { description: 'First changed' } }))
    expect(screen.getByLabelText(/Описание/)).toHaveValue('Second entry')
    expect(screen.queryByText('Изменения сохранены.')).not.toBeInTheDocument()
  })

  it.each(['cancelled', 'deleted'] as const)('does not offer mutations for %s entries', async (status) => {
    const entry = entryFixture({ status })
    const patch = vi.spyOn(entriesApi, 'patch')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    expect(await screen.findByText(`Статус: ${status}`)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Сохранить' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Подтвердить' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Не сохранять' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Описание/)).toBeDisabled()
    expect(patch).not.toHaveBeenCalled()
  })
})

function renderRoute(path: string, onRefresh = vi.fn()) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  const view = render(
    <AuthProvider>
      <RefreshProvider>
        <RefreshProbe onRefresh={onRefresh} />
        <RouterProvider router={router} />
      </RefreshProvider>
    </AuthProvider>,
  )
  return { ...view, router }
}

function RefreshProbe({ onRefresh }: { onRefresh: () => void }) {
  useRefreshSubscription(onRefresh)
  return null
}

function entryFixture(overrides: Partial<Entry> = {}): Entry {
  return {
    id: '44444444-4444-4444-8444-444444444444',
    user_id: '11111111-1111-4111-8111-111111111101',
    type: 'meal',
    status: 'confirmed',
    source_kind: 'text',
    source_ref: { file_id: null, telegram_message_id: 42, label: 'test source' },
    occurred_at: '2026-09-16T12:00:00Z',
    created_at: '2026-09-16T12:01:00Z',
    updated_at: '2026-09-16T12:02:00Z',
    revision: 1,
    payload: { description: 'Base meal', mass_g: 200, nutrients: { energy_kcal: 330, protein_g: null, fat_g: null, carbs_g: null }, nutrients_basis: 'per_100g' },
    field_origins: { description: 'reported', mass_g: 'estimated', 'nutrients.energy_kcal': 'computed' },
    submission_id: 'sub_existing',
    ...overrides,
  }
}

function clone<T>(value: T): T {
  return structuredClone(value)
}
