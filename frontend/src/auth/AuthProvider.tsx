/* eslint-disable react-refresh/only-export-components */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { apiClient, apiMode, ApiError } from '../api/client'
import type { ApiClient, ApiMode } from '../api/client'
import type { TelegramAuthResponse, User } from '../api/types'
import { clearSessionToken, getSessionToken, setSessionToken } from './session'

export type AuthenticatedUser = User

export type AuthState =
  | { status: 'loading' }
  | { status: 'authenticated'; user: AuthenticatedUser }
  | { status: 'authRequired'; message: string }
  | { status: 'networkError'; message: string }
  | { status: 'sessionExpired'; message: string }

interface AuthContextValue {
  state: AuthState
  retry: () => void
  markSessionExpired: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({
  children,
  client = apiClient,
  mode = apiMode,
}: {
  children: ReactNode
  client?: ApiClient
  mode?: ApiMode
}) {
  const [state, setState] = useState<AuthState>({ status: 'loading' })
  const [retryKey, setRetryKey] = useState(0)

  const retry = useCallback(() => {
    setRetryKey((value) => value + 1)
  }, [])

  const markSessionExpired = useCallback(() => {
    clearSessionToken()
    setState({
      status: 'sessionExpired',
      message: 'Сессия истекла. Заново откройте кабинет из Telegram Mini App.',
    })
  }, [])

  useEffect(() => {
    let isActive = true

    async function loadSession() {
      setState({ status: 'loading' })

      if (mode === 'live' && !getSessionToken()) {
        const initData = window.Telegram?.WebApp?.initData
        if (initData) {
          try {
            const auth = await client.post<TelegramAuthResponse, { init_data: string }>('/auth/telegram', { body: { init_data: initData } })
            if (isActive) {
              setSessionToken(auth.session_token)
              setState({ status: 'authenticated', user: auth.user })
            }
          } catch (error) {
            if (isActive) setState({ status: 'authRequired', message: error instanceof Error ? error.message : 'Не удалось войти через Telegram.' })
          }
          return
        }
        setState({
          status: 'authRequired',
          message: 'Откройте Mini App внутри Telegram, чтобы пройти авторизацию.',
        })
        return
      }

      try {
        const user = await client.get<User>('/me')

        if (isActive) {
          setState({ status: 'authenticated', user })
        }
      } catch (error) {
        if (!isActive) {
          return
        }

        if (error instanceof ApiError && error.status === 401) {
          if (mode === 'live' && getSessionToken() && window.Telegram?.WebApp?.initData) {
            clearSessionToken()
            retry()
            return
          }
          markSessionExpired()
          return
        }

        setState({
          status: 'networkError',
          message: 'Не удалось проверить состояние авторизации. Попробуйте еще раз.',
        })
      }
    }

    void loadSession()

    return () => {
      isActive = false
    }
  }, [client, markSessionExpired, mode, retry, retryKey])

  const value = useMemo(
    () => ({
      state,
      retry,
      markSessionExpired,
    }),
    [markSessionExpired, retry, state],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

declare global {
  interface Window { Telegram?: { WebApp?: { initData?: string } } }
}

export function useAuth() {
  const context = useContext(AuthContext)

  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider.')
  }

  return context
}
