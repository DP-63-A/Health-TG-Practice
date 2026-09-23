import { describe, expect, it } from 'vitest'
import { createEntriesApi } from './entries'
import type { ApiClient } from './types'

describe('entries API facade', () => {
  it('passes diary filters and pagination as GET /entries query params', async () => {
    const calls: unknown[][] = []
    const api = createEntriesApi(createClient({
      get: async <TResponse,>(path: string, options?: Parameters<ApiClient['get']>[1]) => { calls.push([path, options]); return { items: [], next_cursor: null } as TResponse },
    }))

    await api.list({
      from: '2026-09-16',
      to: '2026-09-18',
      type: 'meal',
      status: 'confirmed',
      limit: 2,
      cursor: '2',
    })

    expect(calls).toEqual([['/entries', {
      query: {
        from: '2026-09-16',
        to: '2026-09-18',
        type: 'meal',
        status: 'confirmed',
        limit: 2,
        cursor: '2',
      },
      signal: undefined,
    }]])
  })

  it('uses GET /entries/{id} for entry details', async () => {
    const calls: unknown[][] = []
    const api = createEntriesApi(createClient({
      get: async <TResponse,>(path: string, options?: Parameters<ApiClient['get']>[1]) => { calls.push([path, options]); return {} as TResponse },
    }))

    await api.get('22222222-2222-4222-8222-222222222201')

    expect(calls).toEqual([['/entries/22222222-2222-4222-8222-222222222201', { signal: undefined }]])
  })
})

function createClient(overrides: Partial<ApiClient>): ApiClient {
  return {
    get: async <TResponse,>() => ({}) as TResponse,
    post: async <TResponse,>() => ({}) as TResponse,
    patch: async <TResponse,>() => ({}) as TResponse,
    delete: async <TResponse,>() => ({}) as TResponse,
    ...overrides,
  }
}
