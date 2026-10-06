import { Children, cloneElement, isValidElement, useId } from 'react'
import type { HTMLAttributes, ReactElement, ReactNode } from 'react'

interface FormFieldProps {
  label: string
  hint?: string
  error?: string
  children: ReactNode
}

export function FormField({ label, hint, error, children }: FormFieldProps) {
  const generatedId = useId()
  const controls = Children.toArray(children)
  const isControl = (child: ReactNode): child is ReactElement<HTMLAttributes<HTMLElement>> =>
    isValidElement(child) && typeof child.type === 'string' && ['input', 'select', 'textarea'].includes(child.type)
  const control = controls.find(isControl)
  const id = control?.props.id ?? generatedId
  const hintId = `${id}-hint`
  const errorId = `${id}-error`

  return (
    <div className="ui-field">
      <label htmlFor={id}>{label}</label>
      {controls.map((child) => isControl(child) ? cloneElement(child, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': [child.props['aria-describedby'], hint && hintId, error && errorId].filter(Boolean).join(' ') || undefined,
      }) : child)}
      {hint && <small id={hintId}>{hint}</small>}
      {error && <small id={errorId} className="field-error" role="alert">{error}</small>}
    </div>
  )
}
