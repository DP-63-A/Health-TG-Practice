import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type { Entry, EntryFilters, EntryStatus, EntryType } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useRefresh } from '../refresh/RefreshProvider'

const typeLabels: Record<EntryType, string> = { meal: 'Приём пищи', metrics: 'Показатель', checkin: 'Самочувствие', note: 'Заметка' }
const statusLabels: Record<EntryStatus, string> = { draft: 'На проверке', confirmed: 'Подтверждено', cancelled: 'Отменено', deleted: 'Удалено' }

function DiaryPage() {
  const [filters, setFilters] = useState<EntryFilters>({ status: 'confirmed' })
  const [entries, setEntries] = useState<Entry[]>([])
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState('')
  const { refreshVersion } = useRefresh()
  const { markSessionExpired } = useAuth()
  const load = useCallback(async (signal?: AbortSignal) => {
    setError('')
    setState('loading')
    try { const result = await entriesApi.list(filters, signal); setEntries(result.items); setState('ready') }
    catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (cause instanceof ApiError && cause.status === 401) { markSessionExpired(); return }
      setError(cause instanceof Error ? cause.message : 'Не удалось загрузить записи.'); setState('error')
    }
  }, [filters, markSessionExpired])
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load, refreshVersion])

  return <section className="page-section diary-page">
    <div className="section-heading"><div><h2>Дневник</h2><p>Записи здоровья и черновики, ожидающие проверки.</p></div></div>
    <form className="filters" aria-label="Фильтры дневника" onSubmit={(event) => event.preventDefault()}>
      <label>С даты<input type="date" value={filters.from ?? ''} onChange={(e) => setFilters((v) => ({ ...v, from: e.target.value || undefined }))} /></label>
      <label>По дату<input type="date" value={filters.to ?? ''} onChange={(e) => setFilters((v) => ({ ...v, to: e.target.value || undefined }))} /></label>
      <label>Тип<select value={filters.type ?? ''} onChange={(e) => setFilters((v) => ({ ...v, type: (e.target.value || undefined) as EntryType | undefined }))}><option value="">Все</option>{Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>Статус<select value={filters.status ?? ''} onChange={(e) => setFilters((v) => ({ ...v, status: (e.target.value || undefined) as EntryStatus | undefined }))}><option value="">По умолчанию</option>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    </form>
    {state === 'loading' && <p role="status" className="state-message">Загрузка записей…</p>}
    {state === 'error' && <div role="alert" className="state-message error-message"><p>{error}</p><button type="button" onClick={() => { setState('loading'); void load() }}>Повторить</button></div>}
    {state === 'ready' && entries.length === 0 && <p className="state-message">По выбранным фильтрам записей нет.</p>}
    {state === 'ready' && entries.length > 0 && <ul className="entry-list">{entries.map((entry) => <li key={entry.id}><Link to={`/diary/${entry.id}`}><span className="entry-title">{entryTitle(entry)}</span><span>{typeLabels[entry.type]} · {statusLabels[entry.status]}</span><time dateTime={entry.occurred_at}>{new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(entry.occurred_at))}</time></Link></li>)}</ul>}
  </section>
}

function entryTitle(entry: Entry) {
  if ('description' in entry.payload) return entry.payload.description
  if ('text' in entry.payload) return entry.payload.text
  if ('code' in entry.payload) return `${entry.payload.code}: ${entry.payload.value} ${entry.payload.unit}`
  return `${entry.payload.category}: ${entry.payload.score}/5`
}
export default DiaryPage
