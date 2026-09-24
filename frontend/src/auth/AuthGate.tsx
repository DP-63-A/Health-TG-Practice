import type { ReactNode } from 'react'
import { StateView } from '../components/ui'
import type { StateViewVariant } from '../components/ui'
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
        variant="loading"
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
        variant="empty"
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
        variant="sessionExpired"
      />
    )
  }

  return (
    <AuthStateScreen
      title="Ошибка сети"
      message={state.message}
      actionLabel="Повторить"
      onAction={retry}
      variant="error"
    />
  )
}

function AuthStateScreen({
  title,
  message,
  actionLabel,
  onAction,
  variant,
}: {
  title: string
  message: string
  actionLabel?: string
  onAction?: () => void
  variant: StateViewVariant
}) {
  return (
    <main className="auth-screen">
      <section className="auth-panel" aria-label="Состояние входа">
        <p className="app-kicker">Health TG Practice</p>
        <StateView
          actionLabel={actionLabel}
          onAction={onAction}
          message={message}
          title={title}
          titleAs="h1"
          variant={variant}
        />
      </section>
    </main>
  )
}
