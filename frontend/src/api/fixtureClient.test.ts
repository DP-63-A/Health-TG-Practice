import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import { createEntriesApi } from './entries'
import type { ApiClient } from './types'

let fixtureApiClient: ApiClient
let api: ReturnType<typeof createEntriesApi>

describe('FE1-04 fixture transitions', () => {
  beforeEach(async () => {
    vi.resetModules()
    fixtureApiClient = (await import('./fixtureClient')).fixtureApiClient
    api = createEntriesApi(fixtureApiClient)
  })
  it('merges nested nutrients, checks revision and confirms idempotently', async () => {
    const id = '22222222-2222-4222-8222-222222222202'
    const before = await api.get(id)
    const saved = await api.patch(id, {
      expected_revision: before.revision,
      payload: { nutrients: { energy_kcal: 400 } },
    })
    expect(saved.revision).toBe(before.revision + 1)
    expect(saved.payload).toMatchObject({ nutrients: { energy_kcal: 400, protein_g: null, fat_g: null, carbs_g: null } })
    await expect(api.patch(id, { expected_revision: before.revision, payload: { mass_g: 150 } })).rejects.toMatchObject({ status: 409, code: 'VERSION_CONFLICT' })

    const request = { expected_revision: saved.revision, submission_id: 'test-confirm-idempotent' }
    const confirmed = await api.confirm(id, request)
    const repeated = await api.confirm(id, request)
    expect(confirmed.status).toBe('confirmed')
    expect(repeated).toEqual(confirmed)
    expect(confirmed.id).toBe(before.id)
    expect(confirmed.revision).toBe(saved.revision + 1)
    expect(confirmed.field_origins).toEqual(before.field_origins)
  })

  it('requires missing metrics fields before confirmation', async () => {
    const id = '22222222-2222-4222-8222-222222222206'
    const before = await api.get(id)
    await expect(api.confirm(id, { expected_revision: before.revision, submission_id: 'test-metrics' })).rejects.toMatchObject({
      status: 422,
      field_errors: expect.arrayContaining([expect.objectContaining({ field: 'payload.unit' })]),
    } satisfies Partial<ApiError>)
    expect((await api.get(id)).revision).toBe(before.revision)
  })

  it('cancels a draft and excludes it from confirmed entries', async () => {
    const id = '22222222-2222-4222-8222-222222222207'
    const before = await api.get(id)
    const cancelled = await api.cancel(id, before.revision)
    expect(cancelled.status).toBe('cancelled')
    expect(cancelled.revision).toBe(before.revision + 1)
    expect((await api.list({ status: 'confirmed', limit: 100 })).items.some((item) => item.id === id)).toBe(false)
  })

  it('rejects stale or unquoted DELETE and excludes a logically deleted entry', async () => {
    const id = '22222222-2222-4222-8222-222222222201'
    const before = await api.get(id)
    await expect(fixtureApiClient.delete(`/entries/${id}`, { headers: { 'If-Match': String(before.revision) } }))
      .rejects.toMatchObject({ status: 422, code: 'VALIDATION_ERROR' })
    await expect(api.delete(id, before.revision - 1)).rejects.toMatchObject({ status: 409, code: 'VERSION_CONFLICT' })
    expect((await api.get(id)).status).toBe('confirmed')

    const removed = await api.delete(id, before.revision)
    expect(removed).toMatchObject({ id, status: 'deleted', revision: before.revision + 1 })
    expect((await api.list({ status: 'confirmed', limit: 100 })).items.some((item) => item.id === id)).toBe(false)
    await expect(api.delete(id, removed.revision)).rejects.toMatchObject({ status: 409, code: 'INVALID_STATUS_TRANSITION' })
  })
})
