import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type { Entry, EntryPayload, EntryType } from '../api/types'
import { AuthProvider } from '../auth/AuthProvider'
import { AuthGate } from '../auth/AuthGate'
import { RefreshProvider, useRefreshSubscription } from '../refresh/RefreshProvider'
import { appRoutes } from '../router/router'

describe('FE1-04 entry review and correction', () => {
  it.each(['-1', '-0.00001'])('rejects negative pulse %s without sending a patch and accepts an explicit zero', async (negative) => {
    const entry = entryFixture({ type: 'metrics', status: 'draft', payload: { code: 'heart_rate', value: 72 } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2, payload: { code: 'heart_rate', value: 0 } })
    renderRoute('/diary/' + entry.id)
    const value = await screen.findByLabelText(/Value/)
    fireEvent.change(value, { target: { value: negative } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/не может быть отрицательным по контракту/)).toBeInTheDocument()
    expect(patch).not.toHaveBeenCalled()
    expect(value).toHaveAttribute('aria-invalid', 'true')
    expect(value).toHaveFocus()
    fireEvent.change(value, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 1, payload: { value: 0 } }))
  })

  it.each([
    ['metrics', { code: 'heart_rate', value: 72, unit: 'bpm', local_date: '2026-10-06' }, 'Value'],
    ['meal', { description: 'Печёное яблоко 🍎', mass_g: 100 }, 'Weight'],
    ['meal', { description: 'Печёное яблоко 🍎', nutrients: { energy_kcal: 12 } }, 'Calories'],
  ] satisfies Array<[EntryType, EntryPayload, string]>)('blocks underflow in %s / %s / %s without sending zero', async (type, payload, label) => {
    const entry = entryFixture({ type, payload })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2 })
    renderRoute(`/diary/${entry.id}`)
    const field = await screen.findByLabelText(label)
    for (const value of ['-1e-400', '1e-400']) {
      fireEvent.change(field, { target: { value } })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      expect(await screen.findByText(/при отправке оно превратится в ноль/)).toBeInTheDocument()
      expect(field).toHaveValue(value)
      expect(field).toHaveFocus()
      expect(patch).not.toHaveBeenCalled()
    }
    fireEvent.change(field, { target: { value: '0e-400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
  })

  it.each(['heart_rate', 'sleep_duration_min'] as const)('combines %s date correction with accessible errors and preserves known time', async (code) => {
    const entry = entryFixture({ type: 'metrics', status: 'draft', submission_id: null,
      occurred_at: '2026-10-07T23:50:00Z', payload: { code, value: 70,
        unit: code === 'heart_rate' ? 'bpm' : 'min', local_date: '2026-10-06', local_time: '07:15' } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch')
      .mockRejectedValueOnce(new ApiError({ code: 'VALIDATION_ERROR', message: 'Invalid date',
        request_id: 'integration94', field_errors: [{ field: 'payload.local_date', message: 'Проверьте дату показателя' }] }, 422))
      .mockResolvedValueOnce({ ...entry, revision: 2, payload: { ...entry.payload, local_date: '2026-10-04' } })
    renderRoute(`/diary/${entry.id}`)
    const day = await screen.findByLabelText(code === 'heart_rate' ? 'Measurement date' : 'Wake-up date')
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(day).toHaveFocus()
    expect(screen.getByLabelText('Reported at')).toHaveAttribute('readonly')
    fireEvent.change(day, { target: { value: '2026-10-05' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Проверьте дату показателя')
    expect(day).toHaveAttribute('aria-invalid', 'true')
    expect(day).toHaveAccessibleDescription(/Проверьте дату показателя/)
    expect(day).toHaveFocus()
    expect(screen.getByLabelText('Local time')).toHaveValue('07:15')
    fireEvent.change(day, { target: { value: '2026-10-04' } })
    expect(day).not.toHaveAttribute('aria-invalid')
    expect(day).not.toHaveAccessibleDescription(/Проверьте дату показателя/)
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Changes saved.')
    expect(patch).toHaveBeenCalledTimes(2)
    for (const [, body] of patch.mock.calls) {
      expect(body.expected_revision).toBe(1)
      expect(body).not.toHaveProperty('occurred_at')
      expect(body.payload).not.toHaveProperty('local_time')
    }
    expect(screen.getByLabelText('Local time')).toHaveValue('07:15')
  })

  it.each(['heart_rate', 'sleep_duration_min'] as const)('changes %s date without inventing time or replacing original report', async (code) => {
    const entry = entryFixture({ type: 'metrics', status: 'draft', submission_id: null,
      occurred_at: '2026-10-07T23:50:00Z',
      payload: { code, value: 70, unit: code === 'heart_rate' ? 'bpm' : 'min', local_date: '2026-10-06' } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2,
      payload: { ...entry.payload, local_date: '2026-10-05' } })
    renderRoute(`/diary/${entry.id}`)
    const day = await screen.findByLabelText(code === 'heart_rate' ? /Measurement date/ : /Wake-up date/)
    expect(screen.getByLabelText(/Local time/)).toHaveValue('')
    expect(screen.getByLabelText(/Reported at/)).toHaveAttribute('readonly')
    fireEvent.change(day, { target: { value: '2026-10-05' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).toEqual(expect.objectContaining({ expected_revision: 1,
      payload: { local_date: '2026-10-05' } }))
    expect(patch.mock.calls[0][1]).not.toHaveProperty('occurred_at')
  })

  it('edits the steps day without changing report time and focuses the editable day', async () => {
    const entry = entryFixture({ type: 'metrics', status: 'draft', submission_id: null,
      occurred_at: '2026-10-05T06:00:00Z',
      payload: { code: 'steps', value: 9000, unit: 'count', local_date: '2026-10-04' } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2,
      payload: { ...entry.payload, local_date: '2026-10-03' } })
    renderRoute(`/diary/${entry.id}`)
    const day = await screen.findByLabelText(/Steps date/)
    expect(day).toHaveValue('2026-10-04')
    expect(screen.getByLabelText(/Reported at/)).toHaveAttribute('readonly')
    expect(screen.getByLabelText(/Metric/)).toBeDisabled()
    expect(screen.queryByLabelText(/Local time/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(day).toHaveFocus()
    fireEvent.change(day, { target: { value: '2026-10-03' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).toEqual(expect.objectContaining({ expected_revision: 1,
      payload: expect.objectContaining({ local_date: '2026-10-03' }) }))
    expect(patch.mock.calls[0][1]).not.toHaveProperty('occurred_at')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it.each([
    ['meal', { description: 'Meal' }, 'Description', '', 'Description is required.'],
    ['metrics', { code: 'steps', value: 100, unit: 'steps', local_date: '2026-09-16' }, 'Unit', '', 'A unit is required for a confirmed measurement.'],
    ['checkin', { category: 'mood', score: 3 }, 'Score', '0', 'Score must be a whole number from 1 to 5.'],
    ['note', { text: 'Note' }, 'Text', '', 'Text is required.'],
  ] satisfies Array<[EntryType, EntryPayload, string, string, string]>)('connects %s validation to its named field and focuses it', async (type, payload, name, value, error) => {
    const entry = entryFixture({ type, payload })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch')
    renderRoute(`/diary/${entry.id}`)
    if (type === 'checkin') fireEvent.click(await screen.findByRole('button', { name: 'Edit sticker' }))
    const field = await screen.findByLabelText(name)
    fireEvent.change(field, { target: { value } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(error)).toBeInTheDocument()
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(field).toHaveAccessibleDescription(expect.stringContaining(error))
    expect(field).toHaveFocus()
    expect(patch).not.toHaveBeenCalled()
  })

  it.each(['draft', 'confirmed'] as const)('preserves unsaved %s input and focus across viewport resize', async (status) => {
    const entry = entryFixture({ status })
    const get = vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch')
    const confirm = vi.spyOn(entriesApi, 'confirm')
    const viewport = new EventTarget()
    vi.stubGlobal('visualViewport', viewport)
    renderRoute(`/diary/${entry.id}`)
    const description = await screen.findByLabelText('Description')
    fireEvent.change(description, { target: { value: 'Unsaved mobile input' } })
    description.focus()

    for (const [width, height] of [[360, 320], [390, 844], [360, 740]]) {
      vi.stubGlobal('innerWidth', width)
      vi.stubGlobal('innerHeight', height)
      fireEvent(window, new Event('resize'))
      act(() => {
        viewport.dispatchEvent(new Event('resize'))
      })
      expect(screen.getByLabelText('Description')).toBe(description)
      expect(description).toHaveValue('Unsaved mobile input')
      expect(description).toHaveFocus()
      expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
    }
    expect(get).toHaveBeenCalledTimes(1)
    expect(patch).not.toHaveBeenCalled()
    expect(confirm).not.toHaveBeenCalled()
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
    expect(screen.getByLabelText(/Description/)).toHaveValue('Pasta')
    expect(screen.getByLabelText(/Weight/)).toHaveValue('')
    expect(screen.getAllByText('Unknown').length).toBeGreaterThan(0)
    expect(screen.getByRole('region', { name: 'Происхождение полей' })).toHaveTextContent('estimated')
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Discard draft' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(screen.getByLabelText('Date and time')).toHaveFocus()
    expect(screen.queryByRole('button', { name: 'Discard sticker' })).not.toBeInTheDocument()
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

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch).toHaveBeenCalledWith(entry.id, expect.objectContaining({
      expected_revision: 5,
      payload: expect.objectContaining({ mass_g: 150 }),
    }))
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(await screen.findByText('Changes saved.')).toBeInTheDocument()
  })

  it.each([
    ['meal', { description: 'Meal', mass_g: null, nutrients: { energy_kcal: null }, nutrients_basis: 'unknown' }, [/Description/, /Weight/, /Calories/]],
    ['metrics', { code: 'steps', value: 0, unit: null, local_date: null, local_time: null, qualifier: null }, [/Metric/, /Value/, /Unit/, /Steps date/]],
    ['checkin', { category: 'mood', score: 4 }, [/Category/, /Score/]],
    ['note', { text: 'Plain note' }, [/Text/]],
  ] satisfies Array<[EntryType, EntryPayload, RegExp[]]>)('builds the %s form from contract fields', async (type, payload, labels) => {
    const entry = entryFixture({ type, payload })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)
    if (type === 'checkin') {
      const pencil = await screen.findByRole('button', { name: 'Edit sticker' })
      expect(screen.queryByLabelText('Date and time')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Category')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Score')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
      fireEvent.click(pencil)
    }

    expect(await screen.findByRole('group', { name: type === 'meal' ? 'Meal' : type === 'metrics' ? 'Measurement' : type === 'checkin' ? 'Wellbeing' : 'Note' })).toBeInTheDocument()
    for (const label of labels) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
    const fields = screen.getByRole('form', { name: 'Edit entry' }).querySelectorAll('input, select, textarea')
    for (const field of fields) {
      expect(field).toHaveAccessibleName()
      expect(field.id).toBeTruthy()
      for (const id of field.getAttribute('aria-describedby')?.split(' ') ?? []) {
        expect(document.getElementById(id)).toBeInTheDocument()
      }
    }
    const ids = [...document.querySelectorAll('[id]')].map((element) => element.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('confirms a draft with expected_revision and a stable submission_id without duplicate double-click', async () => {
    const entry = entryFixture({ status: 'draft', revision: 2, submission_id: null })
    let resolveConfirm: (entry: Entry) => void = () => undefined
    const confirm = vi.spyOn(entriesApi, 'confirm').mockImplementation(() => new Promise<Entry>((resolve) => { resolveConfirm = resolve }))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    const button = await screen.findByRole('button', { name: 'Confirm' })
    fireEvent.click(button)
    fireEvent.click(button)

    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
    expect(confirm).toHaveBeenCalledWith(entry.id, {
      expected_revision: 2,
      submission_id: expect.stringMatching(new RegExp(`^web_${entry.id}_`)),
    })
    expect(screen.queryByText('Entry confirmed.')).not.toBeInTheDocument()
    expect(button).toBeDisabled()

    await act(async () => {
      resolveConfirm({ ...entry, status: 'confirmed', submission_id: 'server-submission', revision: 3 })
    })

    expect(await screen.findByText('Entry confirmed.')).toBeInTheDocument()
  })

  it('saves changed draft fields before confirm and uses the updated revision', async () => {
    const entry = entryFixture({ status: 'draft', revision: 1, submission_id: null })
    const saved = entryFixture({ ...entry, revision: 2, payload: { description: 'Changed meal', mass_g: 100 } })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue(clone(saved))
    const confirm = vi.spyOn(entriesApi, 'confirm').mockResolvedValue({ ...saved, status: 'confirmed', submission_id: 'sub_web' })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'Changed meal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))

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

    fireEvent.click(await screen.findByRole('button', { name: 'Discard draft' }))

    await waitFor(() => expect(cancel).toHaveBeenCalledWith(entry.id, entry.revision))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  })

  it('shows success only after the PATCH API resolves', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 3 })
    let resolvePatch: (entry: Entry) => void = () => undefined
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    vi.spyOn(entriesApi, 'patch').mockImplementation(() => new Promise<Entry>((resolve) => { resolvePatch = resolve }))

    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'Waiting' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.queryByText('Changes saved.')).not.toBeInTheDocument()

    await act(async () => {
      resolvePatch({ ...entry, revision: 4, payload: { description: 'Waiting' } })
    })

    expect(await screen.findByText('Changes saved.')).toBeInTheDocument()
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

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('mass must be >= 0')).toBeInTheDocument()
    expect(screen.getByLabelText(/Weight/)).toHaveValue('150')
    expect(screen.getByLabelText('Weight')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Weight')).toHaveAccessibleDescription(/mass must be >= 0/)
    await waitFor(() => expect(screen.getByLabelText('Weight')).toHaveFocus())
    expect(screen.queryByText('Changes saved.')).not.toBeInTheDocument()
  })

  it('blocks a locally invalid mass before PATCH and keeps the entered value', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 4 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const patch = vi.spyOn(entriesApi, 'patch')
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/не может быть отрицательным по контракту/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Weight/)).toHaveValue('-1')
    expect(screen.getByLabelText('Weight')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Weight')).toHaveAccessibleDescription(/не может быть отрицательным по контракту/)
    expect(screen.getByLabelText('Weight')).toHaveFocus()
    expect(patch).not.toHaveBeenCalled()
    expect(screen.queryByText('Changes saved.')).not.toBeInTheDocument()
  })

  it('keeps input on network error without false success', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const refresh = vi.fn()
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    vi.spyOn(entriesApi, 'patch').mockRejectedValue(new Error('Network down'))

    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'Local text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Network down')).toBeInTheDocument()
    expect(screen.getByLabelText(/Description/)).toHaveValue('Local text')
    expect(screen.queryByText('Changes saved.')).not.toBeInTheDocument()
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

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'My unsaved text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Version conflict версий/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server text')
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

    expect(await screen.findByLabelText(/Date and time/)).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).not.toHaveProperty('occurred_at')
  })

  it('converts manual date/time by the authenticated user timezone', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 7, occurred_at: '2026-09-16T12:00:00Z' })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 8 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))

    renderRoute(`/diary/${entry.id}`)

    expect(await screen.findByLabelText(/Date and time/)).toHaveValue('2026-09-16T14:00')
    fireEvent.change(screen.getByLabelText(/Date and time/), { target: { value: '2026-09-16T15:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

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

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(2))
  })

  it('edits without confirming and PATCHes only the changed field', async () => {
    const entry = entryFixture({ status: 'draft', revision: 4, submission_id: null })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 5, payload: { ...entry.payload, mass_g: 150 } })
    const confirm = vi.spyOn(entriesApi, 'confirm')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    expect(document.activeElement).toBe(screen.getByLabelText(/Date and time/))
    expect(confirm).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText(/Weight/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 4, payload: { mass_g: 150 } })
    expect(confirm).not.toHaveBeenCalled()
  })

  it('preserves omitted metrics fields and sends only the edited value', async () => {
    const entry = entryFixture({ type: 'metrics', status: 'draft', payload: { code: 'heart_rate', value: 80 } })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2, payload: { code: 'heart_rate', value: 75 } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Value/), { target: { value: '75' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
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
    expect(await screen.findByLabelText(/Weight/)).toHaveValue('0')
    expect(screen.getByLabelText(/Calories/)).toHaveValue('')
    expect(screen.getByLabelText(/Protein/)).toHaveValue('1')
    expect(screen.getByLabelText(/Fat/)).toHaveValue('2')
    expect(screen.getByLabelText(/Carbs/)).toHaveValue('3')
    expect(screen.getByLabelText(/Nutrition basis/)).toHaveValue('per_100g')
  })

  it('shows all optional metrics fields from the contract', async () => {
    const entry = entryFixture({ type: 'metrics', payload: {
      code: 'heart_rate', value: 80, unit: 'bpm', local_date: '2026-09-16',
      local_time: '08:35', qualifier: 'resting',
    } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    expect(await screen.findByLabelText(/Metric/)).toHaveValue('heart_rate')
    expect(screen.getByLabelText(/Value/)).toHaveValue('80')
    expect(screen.getByLabelText(/Unit/)).toHaveValue('bpm')
    expect(screen.getByLabelText(/Measurement date/)).toHaveValue('2026-09-16')
    expect(screen.getByLabelText(/Local time/)).toHaveValue('08:35')
    expect(screen.getByLabelText(/Heart rate context/)).toHaveValue('resting')
  })

  it.each(['sleep_quality', 'digestion_comfort', 'wellbeing', 'mood'] as const)(
    'supports the %s assessment category', async (category) => {
      const entry = entryFixture({ type: 'checkin', payload: { category, score: 3 } })
      const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2, payload: { category, score: 4 } })
      vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
      renderRoute(`/diary/${entry.id}`)
      fireEvent.click(await screen.findByRole('button', { name: 'Edit sticker' }))
      expect(await screen.findByLabelText(/Category/)).toHaveValue(category)
      fireEvent.change(screen.getByLabelText(/Score/), { target: { value: '4' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      await waitFor(() => expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 1, payload: { score: 4 } }))
    },
  )

  it('edits a note through the contract text field and shows the saved value', async () => {
    const entry = entryFixture({ type: 'note', status: 'confirmed', revision: 7, payload: { text: 'Before' } })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 8, payload: { text: 'After' } })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Text/), { target: { value: 'After' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(patch).toHaveBeenCalledWith(entry.id, { expected_revision: 7, payload: { text: 'After' } })
    expect(await screen.findByText('Changes saved.')).toBeInTheDocument()
    expect(screen.getByLabelText(/Text/)).toHaveValue('After')
  })

  it('blocks a rapid second PATCH and a conflicting cancel while saving', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    let rejectPatch: (reason: Error) => void = () => undefined
    const patch = vi.spyOn(entriesApi, 'patch').mockImplementation(() => new Promise<Entry>((_, reject) => { rejectPatch = reject }))
    const cancel = vi.spyOn(entriesApi, 'cancel')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '150' } })
    const save = screen.getByRole('button', { name: 'Save' })
    fireEvent.click(save)
    fireEvent.click(save)
    fireEvent.click(screen.getByRole('button', { name: 'Discard draft' }))
    expect(patch).toHaveBeenCalledTimes(1)
    expect(cancel).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Weight/)).toHaveValue('150')
    await act(async () => rejectPatch(new Error('Network down')))
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('blocks a rapid second cancel and restores controls after a network error', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    let rejectCancel: (reason: Error) => void = () => undefined
    const cancel = vi.spyOn(entriesApi, 'cancel').mockImplementation(() => new Promise<Entry>((_, reject) => { rejectCancel = reject }))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    const button = await screen.findByRole('button', { name: 'Discard draft' })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(cancel).toHaveBeenCalledTimes(1)
    await act(async () => rejectCancel(new Error('Network down')))
    expect(screen.getByRole('button', { name: 'Discard draft' })).toBeEnabled()
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

    fireEvent.change(await screen.findByLabelText(/Calories/), { target: { value: '400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Check energy')).toBeInTheDocument()
    expect(screen.getByLabelText(/Calories/)).toHaveValue('400')
    expect(screen.getByLabelText('Calories')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Calories')).toHaveAccessibleDescription(/Check energy/)
    fireEvent.change(screen.getByLabelText(/Calories/), { target: { value: '390' } })
    expect(screen.getByLabelText('Calories')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('Calories')).not.toHaveAccessibleDescription(/Check energy/)
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(2))
  })

  it('does not convert an unchanged date when saving another field', async () => {
    const entry = entryFixture({ occurred_at: '2026-09-16T12:00:37Z', status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2 })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patch).toHaveBeenCalledTimes(1))
    expect(patch.mock.calls[0][1]).not.toHaveProperty('occurred_at')
  })

  it('rejects a nonexistent DST local time without sending PATCH', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Date and time/), { target: { value: '2026-03-29T02:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('This date or time does not exist or is ambiguous in your time zone.')).toBeInTheDocument()
    expect(patch).not.toHaveBeenCalled()
  })

  it('rejects an ambiguous DST local time rather than silently choosing an offset', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const patch = vi.spyOn(entriesApi, 'patch')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Date and time/), { target: { value: '2026-10-25T02:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('This date or time does not exist or is ambiguous in your time zone.')).toBeInTheDocument()
    expect(patch).not.toHaveBeenCalled()
  })

  it('reuses submission_id after an uncertain network failure on retry', async () => {
    const entry = entryFixture({ status: 'draft', submission_id: null })
    const confirm = vi.spyOn(entriesApi, 'confirm')
      .mockRejectedValueOnce(new Error('Network down'))
      .mockResolvedValueOnce({ ...entry, status: 'confirmed', revision: 2, submission_id: 'stored' })
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('Network down')).toBeInTheDocument()
    expect(screen.queryByText('Entry confirmed.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
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

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'First changed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await act(async () => { await router.navigate(`/diary/${second.id}`) })
    expect(await screen.findByLabelText(/Description/)).toHaveValue('Second entry')
    await act(async () => resolvePatch({ ...first, revision: 2, payload: { description: 'First changed' } }))
    expect(screen.getByLabelText(/Description/)).toHaveValue('Second entry')
    expect(screen.queryByText('Changes saved.')).not.toBeInTheDocument()
  })

  it.each(['cancelled', 'deleted'] as const)('does not offer mutations for %s entries', async (status) => {
    const entry = entryFixture({ status })
    const patch = vi.spyOn(entriesApi, 'patch')
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    expect(await screen.findByText(`Status: ${status}`)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Discard draft' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Description/)).toBeDisabled()
    expect(patch).not.toHaveBeenCalled()
  })

  it('deletes a confirmed entry only after explicit confirmation and one successful API response', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 5 })
    const refresh = vi.fn()
    let resolveDelete: (value: Entry) => void = () => undefined
    const remove = vi.spyOn(entriesApi, 'delete').mockImplementation(() => new Promise<Entry>((resolve) => { resolveDelete = resolve }))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.click(await screen.findByRole('button', { name: 'Discard sticker' }))
    expect(remove).not.toHaveBeenCalled()
    const confirm = screen.getByRole('button', { name: 'Yes, discard' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(remove).toHaveBeenCalledTimes(1)
    expect(remove).toHaveBeenCalledWith(entry.id, 5)
    expect(screen.getByText('Status: confirmed')).toBeInTheDocument()
    expect(screen.queryByText('Sticker discarded.')).not.toBeInTheDocument()
    expect(refresh).not.toHaveBeenCalled()

    await act(async () => resolveDelete({ ...entry, status: 'deleted', revision: 6 }))
    expect(await screen.findByText('Sticker discarded.')).toBeInTheDocument()
    expect(screen.getByText('Status: deleted')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Discard sticker' })).not.toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('keeps a confirmed entry visible and retryable after DELETE network failure', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const refresh = vi.fn()
    const remove = vi.spyOn(entriesApi, 'delete').mockRejectedValue(new Error('Network down'))
    vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.click(await screen.findByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    expect(await screen.findByText('Network down')).toBeInTheDocument()
    expect(screen.getByText('Status: confirmed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Yes, discard' })).toBeEnabled()
    expect(screen.queryByText('Sticker discarded.')).not.toBeInTheDocument()
    expect(remove).toHaveBeenCalledTimes(1)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('uses shared session handling for DELETE 401 without success or invalidation', async () => {
    const entry = entryFixture({ status: 'confirmed' })
    const refresh = vi.fn()
    const get = vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    const remove = vi.spyOn(entriesApi, 'delete').mockRejectedValue(new ApiError({ code: 'UNAUTHORIZED', message: 'Session expired', request_id: 'req_401' }, 401))
    renderRoute(`/diary/${entry.id}`, refresh, true)

    fireEvent.click(await screen.findByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    expect(await screen.findByRole('heading', { name: 'Сессия истекла' })).toBeInTheDocument()
    expect(screen.queryByText('Sticker discarded.')).not.toBeInTheDocument()
    expect(refresh).not.toHaveBeenCalled()
    expect(get).toHaveBeenCalledTimes(1)
    expect(remove).toHaveBeenCalledTimes(1)
  })

  it('shows fresh status on stale DELETE and retries only after consciously adopting revision', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 2, payload: { description: 'Old value', mass_g: 200 } })
    const fresh = entryFixture({ ...entry, revision: 3, payload: { description: 'Server value', mass_g: 175 } })
    const get = vi.spyOn(entriesApi, 'get').mockResolvedValueOnce(clone(entry)).mockResolvedValue(clone(fresh))
    const remove = vi.spyOn(entriesApi, 'delete')
      .mockRejectedValueOnce(new ApiError({ code: 'VERSION_CONFLICT', message: 'stale', request_id: 'req_409' }, 409))
      .mockResolvedValueOnce({ ...fresh, status: 'deleted', revision: 4 })
    const refresh = vi.fn()
    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'My unsaved text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    expect(await screen.findByText(/Version conflict версий/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server value')
    expect(screen.getByRole('region', { name: 'Latest server version' })).toHaveTextContent('Revision: 3')
    expect(remove).toHaveBeenCalledTimes(1)
    expect(remove).toHaveBeenCalledWith(entry.id, 2)
    expect(refresh).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Discard sticker' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Use server version' }))
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    fireEvent.click(screen.getByRole('button', { name: 'Yes, replace my input' }))
    await waitFor(() => expect(screen.getByLabelText(/Description/)).toHaveValue('Server value'))
    fireEvent.click(screen.getByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    await waitFor(() => expect(remove).toHaveBeenCalledTimes(2))
    expect(remove).toHaveBeenLastCalledWith(entry.id, 3)
    expect(get).toHaveBeenCalledTimes(3)
  })

  it('shows current status after stale cancel without another cancel request', async () => {
    const entry = entryFixture({ status: 'draft', revision: 2, submission_id: null })
    const fresh = { ...entry, status: 'confirmed' as const, revision: 3 }
    vi.spyOn(entriesApi, 'get').mockResolvedValueOnce(clone(entry)).mockResolvedValue(clone(fresh))
    const cancel = vi.spyOn(entriesApi, 'cancel').mockRejectedValue(new ApiError({ code: 'INVALID_STATUS_TRANSITION', message: 'already confirmed', request_id: 'req_409' }, 409))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Discard draft' }))
    expect(await screen.findByText(/Действие больше недоступно/)).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Status: confirmed')
    expect(cancel).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Discard draft' })).toBeDisabled()
  })

  it('does not treat a lagging GET as the fresh version after 409', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 2 })
    const fresh = { ...entry, revision: 3 }
    const get = vi.spyOn(entriesApi, 'get')
      .mockResolvedValueOnce(clone(entry))
      .mockResolvedValueOnce(clone(entry))
      .mockResolvedValueOnce(clone(fresh))
    const patch = vi.spyOn(entriesApi, 'patch').mockRejectedValue(new ApiError({ code: 'VERSION_CONFLICT', message: 'stale', request_id: 'req' }, 409))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('The server has not returned a newer version yet. Try again.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Use server version' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Weight/)).toHaveValue('150')
    expect(patch).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Reload server version' }))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(3))
    expect(await screen.findByRole('button', { name: 'Use server version' })).toBeInTheDocument()
    expect(patch).toHaveBeenCalledTimes(1)
  })

  it('shows deleted server status after stale DELETE and cannot delete it again', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 2 })
    const fresh = { ...entry, status: 'deleted' as const, revision: 3 }
    vi.spyOn(entriesApi, 'get').mockResolvedValueOnce(clone(entry)).mockResolvedValue(clone(fresh))
    const remove = vi.spyOn(entriesApi, 'delete').mockRejectedValue(new ApiError({ code: 'INVALID_STATUS_TRANSITION', message: 'already deleted', request_id: 'req' }, 409))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Status: deleted')
    expect(remove).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Use server version' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, replace my input' }))
    expect(await screen.findByText('Status: deleted')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Discard sticker' })).not.toBeInTheDocument()
    expect(remove).toHaveBeenCalledTimes(1)
  })

  it('revokes consent to replace input when a newer snapshot arrives before acceptance', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 2 })
    const fresh3 = entryFixture({ ...entry, revision: 3, payload: { description: 'Server N+1' } })
    const fresh4 = entryFixture({ ...entry, revision: 4, payload: { description: 'Server N+2' } })
    const get = vi.spyOn(entriesApi, 'get')
      .mockResolvedValueOnce(clone(entry))
      .mockResolvedValueOnce(clone(fresh3))
      .mockResolvedValueOnce(clone(fresh4))
    const patch = vi.spyOn(entriesApi, 'patch').mockRejectedValue(new ApiError({ code: 'VERSION_CONFLICT', message: 'stale', request_id: 'req' }, 409))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'My unsaved text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server N+1')
    fireEvent.click(screen.getByRole('button', { name: 'Use server version' }))
    expect(screen.getByRole('button', { name: 'Yes, replace my input' })).toBeInTheDocument()

    fireEvent(window, new Event('blur'))
    fireEvent(window, new Event('focus'))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(3))
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server N+2')
    expect(screen.queryByRole('button', { name: 'Yes, replace my input' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    expect(patch).toHaveBeenCalledTimes(1)
  })

  it('keeps draft input on confirm 409 without retrying confirmation', async () => {
    const entry = entryFixture({ status: 'draft', revision: 2, submission_id: null })
    const fresh = entryFixture({ ...entry, revision: 4, payload: { description: 'Bot changed it' } })
    const get = vi.spyOn(entriesApi, 'get').mockResolvedValueOnce(clone(entry)).mockResolvedValue(clone(fresh))
    const patch = vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 3, payload: { description: 'My edited draft' } })
    const confirm = vi.spyOn(entriesApi, 'confirm').mockRejectedValue(new ApiError({ code: 'VERSION_CONFLICT', message: 'stale', request_id: 'req' }, 409))
    const refresh = vi.fn()
    renderRoute(`/diary/${entry.id}`, refresh)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'My edited draft' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Bot changed it')
    expect(screen.getByLabelText(/Description/)).toHaveValue('My edited draft')
    expect(patch).toHaveBeenCalledTimes(1)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm).toHaveBeenCalledWith(entry.id, expect.objectContaining({ expected_revision: 3, submission_id: expect.any(String) }))
    expect(get).toHaveBeenCalledTimes(2)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled()
  })

  it('does not accept a snapshot that changes during the verification GET', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 2 })
    const fresh3 = entryFixture({ ...entry, revision: 3, payload: { description: 'Server N+1' } })
    const fresh4 = entryFixture({ ...entry, revision: 4, payload: { description: 'Server N+2' } })
    let resolveVerification: (value: Entry) => void = () => undefined
    const get = vi.spyOn(entriesApi, 'get')
      .mockResolvedValueOnce(clone(entry))
      .mockResolvedValueOnce(clone(fresh3))
      .mockImplementationOnce(() => new Promise<Entry>((resolve) => { resolveVerification = resolve }))
    const remove = vi.spyOn(entriesApi, 'delete').mockRejectedValue(new ApiError({ code: 'VERSION_CONFLICT', message: 'stale', request_id: 'req' }, 409))
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'My unsaved text' } })
    fireEvent.click(screen.getByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server N+1')
    fireEvent.click(screen.getByRole('button', { name: 'Use server version' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, replace my input' }))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(3))
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    expect(screen.getByLabelText(/Description/)).toBeDisabled()
    await act(async () => resolveVerification(clone(fresh4)))
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    expect(screen.getByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server N+2')
    expect(screen.queryByRole('button', { name: 'Yes, replace my input' })).not.toBeInTheDocument()
    expect(remove).toHaveBeenCalledTimes(1)
  })

  it('rereads after app focus without replacing unsaved form input', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 1, payload: { description: 'Original', mass_g: 200 } })
    const fresh = entryFixture({ ...entry, revision: 2, payload: { description: 'Server changed', mass_g: 175 } })
    const get = vi.spyOn(entriesApi, 'get').mockResolvedValueOnce(clone(entry)).mockResolvedValue(clone(fresh))
    const patch = vi.spyOn(entriesApi, 'patch')
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'My unsaved text' } })
    fireEvent(window, new Event('blur'))
    fireEvent(window, new Event('focus'))

    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('region', { name: 'Latest server version' })).toHaveTextContent('Server changed')
    expect(screen.getByLabelText(/Description/)).toHaveValue('My unsaved text')
    expect(patch).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('does not create a conflict on lifecycle refresh with unchanged revision', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 2 })
    const get = vi.spyOn(entriesApi, 'get').mockResolvedValue(clone(entry))
    renderRoute(`/diary/${entry.id}`)
    fireEvent.change(await screen.findByLabelText(/Description/), { target: { value: 'Local draft text' } })
    fireEvent(window, new Event('blur'))
    fireEvent(window, new Event('focus'))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    expect(screen.getByLabelText(/Description/)).toHaveValue('Local draft text')
    expect(screen.queryByRole('region', { name: 'Latest server version' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('ignores a stale lifecycle GET that finishes after a successful PATCH', async () => {
    const entry = entryFixture({ status: 'confirmed', revision: 1 })
    let resolveRefresh: (value: Entry) => void = () => undefined
    vi.spyOn(entriesApi, 'get')
      .mockResolvedValueOnce(clone(entry))
      .mockImplementationOnce(() => new Promise<Entry>((resolve) => { resolveRefresh = resolve }))
    vi.spyOn(entriesApi, 'patch').mockResolvedValue({ ...entry, revision: 2, payload: { ...entry.payload, mass_g: 150 } })
    renderRoute(`/diary/${entry.id}`)

    fireEvent.change(await screen.findByLabelText(/Weight/), { target: { value: '150' } })
    fireEvent(window, new Event('blur'))
    fireEvent(window, new Event('focus'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Changes saved.')).toBeInTheDocument()

    await act(async () => resolveRefresh(clone(entry)))
    expect(screen.getByLabelText(/Weight/)).toHaveValue('150')
    expect(screen.queryByRole('region', { name: 'Latest server version' })).not.toBeInTheDocument()
  })

  it('does not apply an old DELETE response to another entry or leave it busy', async () => {
    const first = entryFixture({ status: 'confirmed' })
    const second = entryFixture({ id: '66666666-6666-4666-8666-666666666666', status: 'confirmed', payload: { description: 'Second entry' } })
    let resolveDelete: (value: Entry) => void = () => undefined
    vi.spyOn(entriesApi, 'get').mockImplementation(async (id) => clone(id === first.id ? first : second))
    vi.spyOn(entriesApi, 'delete').mockImplementation(() => new Promise<Entry>((resolve) => { resolveDelete = resolve }))
    const { router } = renderRoute(`/diary/${first.id}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Discard sticker' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, discard' }))
    await act(async () => { await router.navigate(`/diary/${second.id}`) })
    expect(await screen.findByLabelText(/Description/)).toHaveValue('Second entry')
    await act(async () => resolveDelete({ ...first, status: 'deleted', revision: 2 }))
    expect(screen.getByLabelText(/Description/)).toHaveValue('Second entry')
    expect(screen.getByRole('button', { name: 'Discard sticker' })).toBeEnabled()
    expect(screen.queryByText('Sticker discarded.')).not.toBeInTheDocument()
  })
})

function renderRoute(path: string, onRefresh = vi.fn(), withGate = false) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  const view = render(
    <AuthProvider>
      <RefreshProvider>
        <RefreshProbe onRefresh={onRefresh} />
        {withGate ? <AuthGate><RouterProvider router={router} /></AuthGate> : <RouterProvider router={router} />}
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
