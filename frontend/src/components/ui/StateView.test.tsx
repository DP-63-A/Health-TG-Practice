import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Button, StateView } from '.'

describe('shared UI states', () => {
  it('renders loading, empty, error and success as distinct states', () => {
    const { rerender } = render(
      <StateView title="Загрузка записей" message="Получаем дневник." variant="loading" />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('Загрузка записей')

    rerender(<StateView title="Записей нет" message="Это не ошибка API." variant="empty" />)
    expect(screen.getByRole('heading', { name: 'Записей нет' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    rerender(<StateView title="Не удалось загрузить" message="API недоступен." variant="error" />)
    expect(screen.getByRole('alert')).toHaveTextContent('API недоступен.')

    rerender(<StateView title="Готово" message="Данные загружены." variant="success" />)
    expect(screen.getByRole('heading', { name: 'Готово' })).toBeInTheDocument()
  })

  it('calls retry callback once and disables accidental loading retries', () => {
    const onAction = vi.fn()
    const { rerender } = render(
      <StateView actionLabel="Повторить" onAction={onAction} title="Ошибка" variant="error" />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(onAction).toHaveBeenCalledTimes(1)

    rerender(
      <StateView
        actionBusy
        actionLabel="Повторить"
        onAction={onAction}
        title="Ошибка"
        variant="error"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Выполняется…' }))
    expect(onAction).toHaveBeenCalledTimes(1)
  })

  it('blocks repeated retry while async action is pending', () => {
    const onAction = vi.fn(() => new Promise<void>(() => undefined))
    render(
      <StateView actionLabel="Повторить" onAction={onAction} title="Ошибка" variant="error" />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    fireEvent.click(screen.getByRole('button', { name: 'Выполняется…' }))

    expect(onAction).toHaveBeenCalledTimes(1)
  })

  it('keeps session-expired copy human-readable and token-free', () => {
    render(
      <StateView
        actionLabel="Проверить снова"
        message="Сессия истекла. Заново откройте кабинет из Telegram Mini App."
        onAction={() => undefined}
        title="Сессия истекла"
        variant="sessionExpired"
      />,
    )

    expect(screen.getByRole('heading', { name: 'Сессия истекла' })).toBeInTheDocument()
    expect(screen.getByText(/заново откройте кабинет/i)).toBeInTheDocument()
    expect(screen.queryByText(/token|bearer|session_token/i)).not.toBeInTheDocument()
  })
})

describe('shared buttons', () => {
  it('has text name and blocks click while loading', () => {
    const onClick = vi.fn()

    render(
      <Button isLoading onClick={onClick}>
        Сохранить
      </Button>,
    )

    const button = screen.getByRole('button', { name: 'Выполняется…' })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })
})
