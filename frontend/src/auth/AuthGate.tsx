import type { ReactNode } from 'react'
import { useAuth } from './AuthProvider'

export function AuthGate({ children }: { children: ReactNode }) {
  const { state, retry } = useAuth()

  if (state.status === 'authenticated') {
    return children
  }

  if (state.status === 'loading') {
    return (
      <AuthStateScreen
        title="Загрузка"
        message="Проверяем состояние авторизации."
      />
    )
  }

  if (state.status === 'authRequired') {
    return (
      <AuthStateScreen
        title="Требуется авторизация"
        message={state.message}
        actionLabel="Проверить снова"
        onAction={retry}
      />
    )
  }

  if (state.status === 'sessionExpired') {
    return (
      <AuthStateScreen
        title="Сессия истекла"
        message={state.message}
        actionLabel="Проверить снова"
        onAction={retry}
      />
    )
  }

  return (
    <AuthStateScreen
      title="Ошибка сети"
      message={state.message}
      actionLabel="Повторить"
      onAction={retry}
    />
  )
}

function AuthStateScreen({
  title,
  message,
  actionLabel,
  onAction,
}: {
  title: string
  message: string
  actionLabel?: string
  onAction?: () => void
}) {
  return (
    <main className="auth-screen">
      <section className="auth-panel">
        <p className="app-kicker">Health TG Practice</p>
        <h1>{title}</h1>
        <p>{message}</p>
        {actionLabel && onAction ? (
          <button type="button" onClick={onAction}>
            {actionLabel}
          </button>
        ) : null}
      </section>
    </main>
  )
}
