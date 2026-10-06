import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { ApiError } from '../api/errors'
import { fixtureApiClient } from '../api/fixtureClient'
import { AuthGate } from '../auth/AuthGate'
import { AuthProvider } from '../auth/AuthProvider'
import { getAnalytics } from '../overview/analytics'
import { RefreshProvider } from '../refresh/RefreshProvider'
import OverviewPage from './OverviewPage'

vi.mock('../overview/analytics', () => ({
  getAnalytics: vi.fn(),
}))

const getAnalyticsMock = vi.mocked(getAnalytics)

it(
  'shows session expired screen when Overview analytics returns 401',
  async () => {
    getAnalyticsMock.mockRejectedValueOnce(
        new ApiError(
          {
            code: 'session_expired',
            message: 'Session expired',
            request_id: 'overview-401-test',
          },
          401,
        ),
      )

    render(
      <MemoryRouter initialEntries={['/overview']}>
        <AuthProvider client={fixtureApiClient} mode="fixture">
          <AuthGate>
            <RefreshProvider>
              <OverviewPage />
            </RefreshProvider>
          </AuthGate>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(
      await screen.findByRole('heading', {
        name: 'Сессия истекла',
      }),
    ).toBeInTheDocument()

    expect(
      screen.getByText(
        /Заново откройте кабинет из Telegram Mini App/,
      ),
    ).toBeInTheDocument()

    expect(
      screen.getByRole('button', {
        name: 'Проверить снова',
      }),
    ).toBeEnabled()

    expect(getAnalyticsMock).toHaveBeenCalledExactlyOnceWith({
      period: 'days_7',
      timezone: 'Europe/Warsaw',
      checkin_category: 'mood',
    })
  },
)
