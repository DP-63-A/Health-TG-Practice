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
import { clearSessionToken } from './session'

export type AuthenticatedUser = Record<string, unknown>

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
      message: 'Сессия истекла. Повторный вход будет доступен после утверждения auth-контракта.',
    })
  }, [])

  useEffect(() => {
    let isActive = true

    async function loadSession() {
      setState({ status: 'loading' })

      if (mode === 'live') {
        setState({
          status: 'authRequired',
          message: 'Live-авторизация пока не подключена: backend auth contract не утвержден.',
        })
        return
      }

      try {
        const user = await client.get<AuthenticatedUser>('/me')

        if (isActive) {
          setState({ status: 'authenticated', user })
        }
      } catch (error) {
        if (!isActive) {
          return
        }

        if (error instanceof ApiError && error.status === 401) {
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
  }, [client, markSessionExpired, mode, retryKey])

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

export function useAuth() {
  const context = useContext(AuthContext)

  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider.')
  }

  return context
}
