import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import type { ApiClient, ApiMode } from '../api/client'
import { fixtureApiClient } from '../api/fixtureClient'
import { AuthGate } from './AuthGate'
import { AuthProvider } from './AuthProvider'

describe('auth state screens', () => {
  it('shows loading state', () => {
    const client = createClient(() => new Promise(() => {}))

    renderWithAuth(client)

    expect(screen.getByRole('heading', { name: 'Загрузка' })).toBeInTheDocument()
  })

  it('moves fixture mode to authenticated and shows the app', async () => {
    renderWithAuth(fixtureApiClient, 'fixture', <div>Приложение загружено</div>)

    expect(await screen.findByText('Приложение загружено')).toBeInTheDocument()
  })

  it('shows authRequired state for live mode', async () => {
    renderWithAuth(createClient(), 'live')

    expect(
      await screen.findByRole('heading', { name: 'Требуется авторизация' }),
    ).toBeInTheDocument()
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
