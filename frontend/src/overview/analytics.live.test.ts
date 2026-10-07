import { afterEach, expect, it, vi } from 'vitest'
import { createLiveApiClient } from '../api/liveClient'
import { getAnalytics } from './analytics'
import { analyticsFixture } from './fixtures/analytics.fixture'

afterEach(() => vi.unstubAllGlobals())

it.each(['https://example.test/api/v1', 'https://example.test/api/v1/', 'https://example.test/mini-app/api/v1'])('appends the analytics endpoint once to %s', async (base) => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(analyticsFixture), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  }))
  vi.stubGlobal('fetch', fetchMock)
  const query = { period: 'days_7', timezone: 'Europe/Warsaw', checkin_category: 'mood' }
  expect(await getAnalytics(query, createLiveApiClient(base))).toEqual(analyticsFixture)
  const [url, options] = fetchMock.mock.calls[0]
  expect(url.pathname).toBe(base.replace('https://example.test', '').replace(/\/$/, '') + '/analytics')
  expect(url.origin).toBe('https://example.test')
  expect(Object.fromEntries(url.searchParams)).toEqual(query)
  expect(options.method).toBe('GET')
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
