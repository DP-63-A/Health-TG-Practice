import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createEntriesApi } from './entries'
import type { Entry, EntryListResponse } from './types'
import type { AnalyticsResponse } from '../overview/analytics.types'
import { readContractFixture } from '../overview/fixtures/fe2-06-contracts'

beforeEach(() => {
  vi.resetModules()
  vi.stubEnv('VITE_FIXTURE_SCENARIO', 'overview-navigation')
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetModules()
})

describe('FE2-06: общий fixture-сценарий обзора и дневника', () => {
  it('preserves pulse timestamp and unknown time while correcting diary day after merge', async () => {
    const { fixtureApiClient } = await import('./fixtureClient')
    const api = createEntriesApi(fixtureApiClient)
    const id = '22222222-2222-4222-8222-222222222221'
    const before = await api.get(id)
    const saved = await api.patch(id, { expected_revision: before.revision, payload: { local_date: '2026-10-05' } })
    expect(saved.occurred_at).toBe(before.occurred_at)
    expect(saved.payload).toMatchObject({ local_date: '2026-10-05', local_time: null })
    expect((await api.list({ type: 'metrics', from: '2026-10-05', to: '2026-10-05', limit: 100 })).items.map(item => item.id)).toContain(id)
    await expect(api.patch(id, { expected_revision: before.revision, payload: { value: 80 } })).rejects.toMatchObject({ status: 409 })
    await expect(api.patch(id, { expected_revision: saved.revision, occurred_at: '2026-10-05T00:00:00Z' })).rejects.toMatchObject({ status: 422 })
    await expect(api.patch(id, { expected_revision: saved.revision, payload: { code: 'sleep_duration_min' } })).rejects.toMatchObject({ status: 422 })
    expect(await api.get(id)).toEqual(saved)
  })

  it.each([-1, -0.00001])('rejects pulse %s without changing the stored entry and accepts zero', async (negative) => {
    const { fixtureApiClient } = await import('./fixtureClient')
    const api = createEntriesApi(fixtureApiClient)
    const id = '22222222-2222-4222-8222-222222222221'
    const before = await api.get(id)
    await expect(api.patch(id, { expected_revision: before.revision, payload: { value: negative } })).rejects.toMatchObject({
      status: 422, field_errors: expect.arrayContaining([expect.objectContaining({ field: 'payload.value' })]),
    })
    expect(await api.get(id)).toEqual(before)
    const zero = await api.patch(id, { expected_revision: before.revision, payload: { value: 0 } })
    expect(zero.payload).toMatchObject({ code: 'heart_rate', value: 0 })
  })

  it('возвращает неизменённый эталон normal за 7 дней', async () => {
    const { fixtureApiClient } = await import('./fixtureClient')
    const result = await fixtureApiClient.get<AnalyticsResponse>(
      '/analytics',
      { query: { period: 'days_7', checkin_category: 'mood' } },
    )
    expect(result).toEqual(readContractFixture('normal'))
  })

  it('возвращает каждый источник аналитики как запись дневника', async () => {
    const { fixtureApiClient } = await import('./fixtureClient')
    const expected = readContractFixture('normal')
    for (const source of expected.sources) {
      const actual = await fixtureApiClient.get<Entry>(
        '/entries/' + source.entry_id,
      )
      expect(actual.id).toBe(source.entry_id)
      expect(actual.type).toBe(source.type)
      expect(actual.status).toBe('confirmed')
    }
  })

  it('находит источники графика шагов среди показателей дня с пагинацией', async () => {
    const { fixtureApiClient } = await import('./fixtureClient')
    const items: Entry[] = []
    let cursor: string | undefined
    for (let page = 0; page < 10; page += 1) {
      const result = await fixtureApiClient.get<EntryListResponse>('/entries', {
        query: {
          status: 'confirmed', type: 'metrics',
          from: '2026-09-19', to: '2026-09-19',
          limit: 2, cursor,
        },
      })
      items.push(...result.items)
      if (!result.next_cursor) break
      cursor = result.next_cursor
    }
    expect(items.map((item) => item.id)).toEqual([
      '22222222-2222-4222-8222-222222222214',
      '22222222-2222-4222-8222-222222222215',
      '22222222-2222-4222-8222-222222222221',
    ])
    const selected = items.find((item) =>
      item.id === '22222222-2222-4222-8222-222222222215',
    )
    expect(selected?.payload).toMatchObject({
      code: 'steps', value: 5000, local_date: '2026-09-19',
    })
  })

  it('источник выбранной категории существует с правильной оценкой', async () => {
    const { fixtureApiClient } = await import('./fixtureClient')
    const response = await fixtureApiClient.get<AnalyticsResponse>(
      '/analytics',
      { query: { period: 'days_7', checkin_category: 'wellbeing' } },
    )
    expect(response.series.checkin.category).toBe('wellbeing')
    const point = response.series.checkin.points[0]
    expect(point.source?.entry_id).toBe(
      '22222222-2222-4222-8222-222222222224',
    )
    const actual = await fixtureApiClient.get<Entry>(
      '/entries/' + point.source!.entry_id,
    )
    expect(actual.payload).toEqual({ category: 'wellbeing', score: 3 })
  })

  it.each(['today', 'days_21'] as const)(
    'не выдаёт семидневный эталон за период %s',
    async (period) => {
      const { fixtureApiClient } = await import('./fixtureClient')
      await expect(fixtureApiClient.get('/analytics', {
        query: { period },
      })).rejects.toMatchObject({
        status: 501,
        code: 'fixture_period_unavailable',
      })
    },
  )

  it('без выбора сценария сохраняет обычные записи FE1', async () => {
    vi.stubEnv('VITE_FIXTURE_SCENARIO', '')
    const { fixtureApiClient } = await import('./fixtureClient')
    const actual = await fixtureApiClient.get<Entry>(
      '/entries/22222222-2222-4222-8222-222222222201',
    )
    expect(actual.payload).toMatchObject({ description: 'Овсянка с ягодами' })
    await expect(fixtureApiClient.get(
      '/entries/22222222-2222-4222-8222-222222222210',
    )).rejects.toMatchObject({ status: 404 })
  })
})
