import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { ApiClient, ApiMode } from '../api/client'
import { fixtureApiClient } from '../api/fixtureClient'
import { AuthGate } from './AuthGate'
import { AuthProvider } from './AuthProvider'
import { clearSessionToken, getSessionToken } from './session'

describe('auth state screens', () => {
  beforeEach(() => {
    clearSessionToken()
    vi.stubGlobal('Telegram', undefined)
  })

  afterEach(() => {
    clearSessionToken()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows loading state', () => {
    const client = createClient(() => new Promise(() => {}))

    renderWithAuth(client)

    expect(screen.getByRole('heading', { name: 'Загрузка' })).toBeInTheDocument()
  })

  it('moves fixture mode to authenticated and shows the app', async () => {
    renderWithAuth(fixtureApiClient, 'fixture', <div>Приложение загружено</div>)

    expect(await screen.findByText('Приложение загружено')).toBeInTheDocument()
  })

  it('authenticates live mode with the existing Telegram initData', async () => {
    const initData = 'telegram-test-payload'
    vi.stubGlobal('Telegram', { WebApp: { initData } })
    const post = vi.spyOn(fixtureApiClient, 'post')

    renderWithAuth(fixtureApiClient, 'live')

    expect(await screen.findByText('App')).toBeInTheDocument()
    expect(post).toHaveBeenCalledExactlyOnceWith('/auth/telegram', {
      body: { init_data: initData },
    })
    expect(getSessionToken()).toBe('fixture-session')
  })

  it.each([
    undefined,
    {},
    { WebApp: {} },
    { WebApp: { initData: '' } },
  ])('shows authRequired without Telegram initData (%j)', async (telegram) => {
    vi.stubGlobal('Telegram', telegram)
    const client = createClient()
    const post = vi.spyOn(client, 'post')
    const get = vi.spyOn(client, 'get')
    renderWithAuth(client, 'live')

    expect(
      await screen.findByRole('heading', { name: 'Требуется авторизация' }),
    ).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
    expect(get).not.toHaveBeenCalled()
  })

  it('shows authRequired when Telegram authentication fails', async () => {
    vi.stubGlobal('Telegram', { WebApp: { initData: 'telegram-test-payload' } })
    const client = createClient()
    vi.spyOn(client, 'post').mockRejectedValue(new Error('Authentication failed'))

    renderWithAuth(client, 'live')

    expect(await screen.findByRole('heading', { name: 'Требуется авторизация' })).toBeInTheDocument()
    expect(screen.getByText('Authentication failed')).toBeInTheDocument()
    expect(getSessionToken()).toBeNull()
  })

  it('shows networkError state', async () => {
    const client = createClient(() => Promise.reject(new Error('offline')))

    renderWithAuth(client)

    expect(
      await screen.findByRole('heading', { name: 'Ошибка сети' }),
    ).toBeInTheDocument()
  })

  it('shows sessionExpired state', async () => {
    const client = createClient(() =>
      Promise.reject(
        new ApiError(
          {
            code: 'session_expired',
            message: 'Session expired',
            request_id: 'test-request',
          },
          401,
        ),
      ),
    )

    renderWithAuth(client)

    expect(
      await screen.findByRole('heading', { name: 'Сессия истекла' }),
    ).toBeInTheDocument()
  })
})

function renderWithAuth(
  client: ApiClient,
  mode: ApiMode = 'fixture',
  children = <div>App</div>,
) {
  return render(
    <AuthProvider client={client} mode={mode}>
      <AuthGate>{children}</AuthGate>
    </AuthProvider>,
  )
}

function createClient(
  get: ApiClient['get'] = async <TResponse,>() => ({}) as TResponse,
): ApiClient {
  return {
    get,
    post: async <TResponse,>() => ({}) as TResponse,
    patch: async <TResponse,>() => ({}) as TResponse,
    delete: async <TResponse = void,>() => undefined as TResponse,
  }
}
