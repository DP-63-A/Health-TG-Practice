import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSessionToken, setSessionToken } from '../auth/session'
import { createLiveApiClient } from './liveClient'

describe('live API URL and authorization', () => {
  afterEach(() => {
    clearSessionToken()
    vi.restoreAllMocks()
  })

  it('resolves a same-origin base path and keeps the bearer token out of the URL', async () => {
    setSessionToken('private-session')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    await createLiveApiClient('/api/v1').get('/entries')

    const [request, init] = fetchMock.mock.calls[0]
    expect(String(request)).toBe(new URL('/api/v1/entries', window.location.origin).toString())
    expect(String(request)).not.toContain('private-session')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer private-session')
  })
})
