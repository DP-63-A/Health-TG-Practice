import { useEffect, useState, type FormEvent } from 'react'

type TelegramPicker = { ready?: () => void; sendData?: (data: string) => void; close?: () => void }
import type { PickerContext } from './context'

export function DateTimePicker({ context, telegram = (window as unknown as {
  Telegram?: { WebApp?: TelegramPicker }
}).Telegram?.WebApp }: { context: PickerContext | null; telegram?: TelegramPicker }) {
  const [date, setDate] = useState(context?.date ?? '')
  const [time, setTime] = useState(context?.time ?? '')
  const [error, setError] = useState('')
  const [sent, setSent] = useState(false)
  useEffect(() => { telegram?.ready?.() }, [telegram])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!context || sent) return
    if (!date || (context.withTime && !time)) { setError('Выберите дату и необходимое время.'); return }
    if (!telegram?.sendData) { setError('Откройте это окно кнопкой в чате Telegram.'); return }
    try {
      setSent(true)
      telegram.sendData(JSON.stringify({ v: 1, token: context.token, revision: context.revision, date,
        ...(context.withTime ? { time } : {}) }))
      // sendData closes reply Mini Apps. The bot, not this page, confirms storage success.
    } catch {
      setSent(false)
      setError('Не удалось передать выбор. Попробуйте ещё раз или вернитесь к ручному вводу в чате.')
    }
  }

  if (!context) return <main className="picker"><h1>Окно недоступно</h1>
    <p>Вернитесь в чат и откройте актуальную кнопку выбора даты.</p></main>

  return <main className="picker">
    <span className="picker-eyebrow">ДНЕВНИК · ДАТА И ВРЕМЯ</span>
    <h1>{context.label}</h1>
    <p className="picker-zone">Часовой пояс профиля: <strong>{context.zone}</strong></p>
    <form onSubmit={submit}>
      <label htmlFor="picker-date">Дата{context.dateLocked ? ' — уже указана' : ''}</label>
      <input id="picker-date" type="date" min="0001-01-01" max="9999-12-31" required value={date}
        readOnly={context.dateLocked} onChange={event => setDate(event.target.value)} />
      {context.withTime && <>
        <label htmlFor="picker-time">Время{context.timeLocked ? ' — уже указано' : ''}</label>
        <input id="picker-time" type="time" step="any" required value={time.slice(0, 12)}
          readOnly={context.timeLocked} onChange={event => setTime(event.target.value)} />
      </>}
      <p className="picker-hint">Выбор вернётся в текущий диалог. Результат применения покажет бот. Запись подтверждается отдельно.</p>
      {error && <p role="alert" className="picker-error">{error}</p>}
      {sent && <p role="status">Выбор передан. Проверьте ответ бота в чате.</p>}
      <button type="submit" disabled={sent}>Применить</button>
      <button className="picker-cancel" type="button" onClick={() => telegram?.close?.()}>Отмена</button>
    </form>
  </main>
}
