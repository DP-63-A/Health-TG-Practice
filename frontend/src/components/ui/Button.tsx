import type { ButtonHTMLAttributes, ReactNode } from 'react'

type ButtonVariant = 'primary' | 'secondary' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  isLoading?: boolean
  children: ReactNode
}

export function Button({
  variant = 'primary',
  isLoading = false,
  disabled,
  className,
  children,
  ...props
}: ButtonProps) {
  const classes = ['ui-button', `ui-button--${variant}`, className].filter(Boolean).join(' ')

  return (
    <button className={classes} disabled={disabled || isLoading} type="button" {...props}>
      {isLoading ? 'Выполняется…' : children}
    </button>
  )
}
