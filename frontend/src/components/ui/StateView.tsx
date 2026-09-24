import { useState } from 'react'
import type { ReactNode } from 'react'
import { Button } from './Button'

export type StateViewVariant = 'loading' | 'empty' | 'error' | 'success' | 'sessionExpired'

interface StateViewProps {
  variant: StateViewVariant
  title: string
  message?: string
  actionLabel?: string
  onAction?: () => void | Promise<void>
  actionBusy?: boolean
  titleAs?: 'h1' | 'h2' | 'h3'
  children?: ReactNode
}

export function StateView({
  variant,
  title,
  message,
  actionLabel,
  onAction,
  actionBusy = false,
  titleAs: Heading = 'h2',
  children,
}: StateViewProps) {
  const [isActionPending, setIsActionPending] = useState(false)
  const role = variant === 'error' ? 'alert' : variant === 'loading' ? 'status' : undefined
  const actionVariant = variant === 'error' || variant === 'sessionExpired' ? 'primary' : 'secondary'
  const isBusy = actionBusy || isActionPending

  async function handleAction() {
    if (!onAction || isBusy) return
    setIsActionPending(true)
    try {
      await onAction()
    } finally {
      setIsActionPending(false)
    }
  }

  return (
    <div className={`ui-state ui-state--${variant}`} role={role}>
      <Heading>{title}</Heading>
      {message && <p>{message}</p>}
      {children}
      {actionLabel && onAction && (
        <Button isLoading={isBusy} onClick={() => void handleAction()} variant={actionVariant}>
          {actionLabel}
        </Button>
      )}
    </div>
  )
}

type NamedStateProps = Omit<StateViewProps, 'variant'>

export function LoadingState(props: NamedStateProps) {
  return <StateView {...props} variant="loading" />
}

export function EmptyState(props: NamedStateProps) {
  return <StateView {...props} variant="empty" />
}

export function ErrorState(props: NamedStateProps) {
  return <StateView {...props} variant="error" />
}
