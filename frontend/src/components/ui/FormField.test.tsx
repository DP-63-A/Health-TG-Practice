import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FormField } from './FormField'

describe('form field accessibility', () => {
  it('keeps names separate from descriptions and gives repeated fields unique IDs', () => {
    render(<>
      <FormField label="Value" hint="First hint"><input /></FormField>
      <FormField label="Value" hint="Second hint"><input /></FormField>
    </>)

    const [first, second] = screen.getAllByRole('textbox', { name: 'Value' })
    expect(first.id).toBeTruthy()
    expect(second.id).not.toBe(first.id)
    expect(first).toHaveAccessibleDescription('First hint')
    expect(second).toHaveAccessibleDescription('Second hint')
  })

  it.each(['input', 'select', 'textarea'] as const)('associates a textual error with %s and clears it after correction', (tag) => {
    const control = tag === 'select' ? <select><option>Option</option></select> : tag === 'textarea' ? <textarea /> : <input />
    const view = render(<FormField label="Value" hint="Units" error="Value is required">{control}</FormField>)
    const field = screen.getByLabelText('Value')
    const error = screen.getByRole('alert')

    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(field).toHaveAccessibleDescription('Units Value is required')
    expect(field.getAttribute('aria-describedby')?.split(' ')).toContain(error.id)
    expect(error).toHaveTextContent('Value is required')

    view.rerender(<FormField label="Value" hint="Units">{control}</FormField>)
    expect(field).not.toHaveAttribute('aria-invalid')
    expect(field).toHaveAccessibleDescription('Units')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    for (const id of field.getAttribute('aria-describedby')!.split(' ')) {
      expect(document.getElementById(id)).toBeInTheDocument()
    }
  })

  it('preserves an explicit control ID and an existing description', () => {
    render(<>
      <p id="external-help">External help</p>
      <FormField label="Value"><input id="existing-field" aria-describedby="external-help" /></FormField>
    </>)
    expect(screen.getByRole('textbox', { name: 'Value' })).toHaveAttribute('id', 'existing-field')
    expect(screen.getByLabelText('Value')).toHaveAccessibleDescription('External help')
  })

  it('does not reference absent hints or errors', () => {
    render(<FormField label="Value"><input /></FormField>)
    expect(screen.getByLabelText('Value')).not.toHaveAttribute('aria-describedby')
  })
})
