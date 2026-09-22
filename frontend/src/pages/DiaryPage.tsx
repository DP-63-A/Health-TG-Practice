import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type { Entry, EntryFilters, EntryPayload, EntryStatus, EntryType, FieldOrigin } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { Badge, Button, Card, EmptyState, ErrorState, FormField, LoadingState } from '../components/ui'
import { useRefresh } from '../refresh/RefreshProvider'

const PAGE_SIZE = 2
const typeLabels: Record<EntryType, string> = { meal: 'Приём пищи', metrics: 'Показатель', checkin: 'Самочувствие', note: 'Заметка' }
const statusLabels: Record<EntryStatus, string> = { draft: 'На проверке', confirmed: 'История', cancelled: 'Отменено', deleted: 'Удалено' }
const originLabels: Record<FieldOrigin, string> = { reported: 'сообщено', extracted: 'извлечено', estimated: 'оценка', computed: 'рассчитано' }

function DiaryPage() {
  const [filters, setFilters] = useState<EntryFilters>({ status: 'confirmed', limit: PAGE_SIZE })
  const [entries, setEntries] = useState<Entry[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [pageIndex, setPageIndex] = useState(0)
  const [pageCursors, setPageCursors] = useState<(string | undefined)[]>([undefined])
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState('')
  const requestIdRef = useRef(0)
  const { refreshVersion } = useRefresh()
  const { markSessionExpired } = useAuth()
  const activeFilters = useMemo<EntryFilters>(() => ({ ...filters, cursor: pageCursors[pageIndex], limit: PAGE_SIZE }), [filters, pageCursors, pageIndex])

  const load = useCallback(async (signal?: AbortSignal) => {
    const requestId = requestIdRef.current + 1
    requestIdRef.current = requestId
    setError('')
    setState('loading')
    setEntries([])
    try {
      const result = await entriesApi.list(activeFilters, signal)
      if (requestId !== requestIdRef.current) return
      setEntries(result.items)
      setNextCursor(result.next_cursor ?? null)
      setState('ready')
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (requestId !== requestIdRef.current) return
      if (cause instanceof ApiError && cause.status === 401) { markSessionExpired(); return }
      setError(cause instanceof Error ? cause.message : 'Не удалось загрузить записи.'); setState('error')
    }
  }, [activeFilters, markSessionExpired])

  useEffect(() => {
    const controller = new AbortController()
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(controller.signal)
    return () => { controller.abort(); requestIdRef.current += 1 }
  }, [load, refreshVersion])

  function updateFilters(patch: Partial<EntryFilters>) {
    setFilters((value) => ({ ...value, ...patch, limit: PAGE_SIZE, cursor: undefined }))
    setPageIndex(0)
    setPageCursors([undefined])
    setNextCursor(null)
  }

  function goNext() {
    if (!nextCursor) return
    setPageCursors((value) => [...value.slice(0, pageIndex + 1), nextCursor])
    setPageIndex((value) => value + 1)
  }

  return <Card className="diary-page" subtitle="История confirmed и отдельный режим проверки draft." title="Дневник">
    <form className="filters" aria-label="Фильтры дневника" onSubmit={(event) => event.preventDefault()}>
      <FormField label="Режим"><select value={filters.status ?? 'confirmed'} onChange={(e) => updateFilters({ status: e.target.value as EntryStatus })}><option value="confirmed">История</option><option value="draft">Проверка</option></select></FormField>
      <FormField label="С даты"><input type="date" value={filters.from ?? ''} onChange={(e) => updateFilters({ from: e.target.value || undefined })} /></FormField>
      <FormField label="По дату"><input type="date" value={filters.to ?? ''} onChange={(e) => updateFilters({ to: e.target.value || undefined })} /></FormField>
      <FormField label="Тип"><select value={filters.type ?? ''} onChange={(e) => updateFilters({ type: (e.target.value || undefined) as EntryType | undefined })}><option value="">Все</option>{Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></FormField>
    </form>

    {state === 'loading' && <LoadingState title="Загрузка записей" message="Получаем страницу дневника из API." />}
    {state === 'error' && <ErrorState title="Не удалось загрузить записи" message={error} actionLabel="Повторить" onAction={() => load()} />}
    {state === 'ready' && entries.length === 0 && <EmptyState title="Записей нет" message="По выбранным серверным фильтрам ничего не найдено." />}
    {state === 'ready' && entries.length > 0 && <>
      <ul className="entry-list">{entries.map((entry) => <li key={entry.id}><Link to={`/diary/${entry.id}`}>
        <span className="entry-title">{entryTitle(entry)}</span>
        <span>{typeLabels[entry.type]} · {statusLabels[entry.status]} · revision {entry.revision}</span>
        <time dateTime={entry.occurred_at}>{formatDateTime(entry.occurred_at)}</time>
        <span>Источник: {sourceLabel(entry)}</span>
        <span>{payloadSummary(entry.payload)}</span>
        <span>{originsSummary(entry.field_origins)}</span>
      </Link></li>)}</ul>
      <div className="pagination-actions" aria-label="Пагинация дневника">
        <Button disabled={pageIndex === 0} onClick={() => setPageIndex((value) => Math.max(0, value - 1))} variant="secondary">Назад</Button>
        <Badge tone="neutral">Страница {pageIndex + 1}</Badge>
        <Button disabled={!nextCursor} onClick={goNext} variant="secondary">Вперёд</Button>
      </div>
    </>}
  </Card>
}

function entryTitle(entry: Entry) {
  if ('description' in entry.payload) return entry.payload.description
  if ('text' in entry.payload) return entry.payload.text
  if ('code' in entry.payload) return `${entry.payload.code}: ${formatValue(entry.payload.value)} ${entry.payload.unit}`
  return `${entry.payload.category}: ${formatValue(entry.payload.score)}/5`
}

function payloadSummary(payload: EntryPayload) {
  if ('description' in payload) return `Масса: ${formatValue(payload.mass_g)} г; ккал: ${formatValue(payload.nutrients?.energy_kcal)}`
  if ('text' in payload) return payload.text
  if ('code' in payload) return `Значение: ${formatValue(payload.value)} ${payload.unit}`
  return `Оценка: ${formatValue(payload.score)} из 5`
}

function sourceLabel(entry: Entry) {
  return entry.source_ref.label ?? entry.source_kind
}

function originsSummary(origins: Entry['field_origins']) {
  const items = Object.entries(origins)
  if (items.length === 0) return 'Происхождение полей не указано'
  return items.slice(0, 3).map(([field, origin]) => `${field}: ${originLabels[origin]}`).join('; ')
}

function formatValue(value: number | string | null | undefined) {
  return value === null || value === undefined ? 'неизвестно' : String(value)
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export default DiaryPage
