import type { HTMLAttributes, ReactNode } from 'react'

interface CardProps extends HTMLAttributes<HTMLElement> {
  as?: 'section' | 'article' | 'div'
  title?: string
  subtitle?: string
  actions?: ReactNode
  children: ReactNode
}

export function Card({
  as: Component = 'section',
  title,
  subtitle,
  actions,
  className,
  children,
  ...props
}: CardProps) {
  const classes = ['ui-card', className].filter(Boolean).join(' ')

  return (
    <Component className={classes} {...props}>
      {(title || subtitle || actions) && (
        <div className="ui-card__header">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p>{subtitle}</p>}
          </div>
          {actions && <div className="ui-card__actions">{actions}</div>}
        </div>
      )}
      {children}
    </Component>
  )
}
