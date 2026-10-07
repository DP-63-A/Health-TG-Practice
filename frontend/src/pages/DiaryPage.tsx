import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useOutletContext } from 'react-router-dom'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type { Entry, EntryFilters, EntryPayload, EntryStatus, EntryType} from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { HandDrawnOutline } from '../components/HandDrawnOutline'
import { Button, Card, EmptyState, ErrorState, FormField, LoadingState } from '../components/ui'
import { useRefresh } from '../refresh/RefreshProvider'

const PAGE_SIZE = 2
const typeLabels: Record<EntryType, string> = { meal: 'Meal', metrics: 'Measurement', checkin: 'Check-in', note: 'Note' }
const categoryLabels = { sleep_quality: 'Sleep quality', digestion_comfort: 'Digestive comfort', wellbeing: 'Wellbeing', mood: 'Mood' }
const metricLabels = { steps: 'Steps', sleep_duration_min: 'Sleep duration', heart_rate: 'Heart rate' }
const overviewTypes = ['meal', 'metrics', 'checkin'] as const satisfies readonly EntryType[]

function isOverviewType(value: string | null): value is (typeof overviewTypes)[number] {
  return value !== null && overviewTypes.some((type) => type === value)
}

function initialFilters(search: string): EntryFilters {
  const params = new URLSearchParams(search)
  const filters: EntryFilters = { status: 'confirmed', limit: PAGE_SIZE }
  const from = params.get('from')
  const to = params.get('to')
  const type = params.get('type')

  if (isCalendarDate(from)) filters.from = from
  if (isCalendarDate(to)) filters.to = to
  if (isOverviewType(type)) filters.type = type
  return filters
}

function isCalendarDate(value: string | null): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function DiaryPage() {
  const { search } = useLocation()
  return <DiaryContent key={search} search={search} />
}

function DiaryContent({ search }: { search: string }) {

  const location = useLocation()
  const { filtersOpen } = useOutletContext<{ filtersOpen: boolean }>()
  const [filters, setFilters] = useState<EntryFilters>(() => initialFilters(search))
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
      setError(cause instanceof Error ? cause.message : 'Could not load entries.'); setState('error')
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

  return <Card className="diary-page" aria-label="Diary">
    {location.state?.notice && (
  <p role="status">{location.state.notice}</p>
      )}
    <div id="diary-filters" className="diary-sorting" hidden={!filtersOpen}>
      <form className="filters" aria-label="Diary filters" onSubmit={(event) => event.preventDefault()}>
      <FormField label="Mode"><select value={filters.status ?? 'confirmed'} onChange={(e) => updateFilters({ status: e.target.value as EntryStatus })}><option value="confirmed">History</option><option value="draft">Review</option></select></FormField>
      <FormField label="From date"><input type="date" value={filters.from ?? ''} onChange={(e) => updateFilters({ from: e.target.value || undefined })} /></FormField>
      <FormField label="To date"><input type="date" value={filters.to ?? ''} onChange={(e) => updateFilters({ to: e.target.value || undefined })} /></FormField>
      <FormField label="Type"><select value={filters.type ?? ''} onChange={(e) => updateFilters({ type: (e.target.value || undefined) as EntryType | undefined })}><option value="">All</option>{Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></FormField>
      </form>
    </div>

    {state === 'loading' && <LoadingState title="Loading entries" message="Loading your diary entries." />}
    {state === 'error' && <ErrorState title="Could not load entries" message={error} actionLabel="Retry" onAction={() => load()} />}
    {state === 'ready' && entries.length === 0 && <EmptyState title="No entries" message={filters.from || filters.to || filters.type || filters.status === 'draft'
      ? 'No entries match the selected filters.'
      : 'Your diary is empty. Add an entry through the Telegram bot, then refresh your diary.'} />}
    {state === 'ready' && entries.length > 0 && <>
      <ul className="entry-list">{entries.map((entry, index) => <li key={entry.id}><Link to={`/diary/${entry.id}`}>
        <HandDrawnOutline index={pageIndex * PAGE_SIZE + index} />
        <div>
          <span className="entry-title">{entryTitle(entry)}</span>
          {entry.type === 'metrics' && 'code' in entry.payload
            ? <span>{entry.payload.code === 'steps' ? 'Step total date' : entry.payload.code === 'sleep_duration_min' ? 'Wake date' : 'Measurement date'}: {entry.payload.local_date || 'unknown'}</span>
            : <time dateTime={entry.occurred_at}>{formatDateTime(entry.occurred_at)}</time>}
          {entry.type !== 'checkin' && <span>{payloadSummary(entry.payload)}</span>}
        </div>
      </Link></li>)}</ul>
      <div className="pagination-actions" aria-label="Diary pagination">
        <Button aria-label="Previous" disabled={pageIndex === 0} onClick={() => setPageIndex((value) => Math.max(0, value - 1))} variant="secondary">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d="M15 6l-6 6 6 6" /></svg>
        </Button>
        <output aria-label="Current page">{pageIndex + 1}</output>
        <Button aria-label="Next" disabled={!nextCursor} onClick={goNext} variant="secondary">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d="M9 6l6 6-6 6" /></svg>
        </Button>
      </div>
    </>}
  </Card>
}

function entryTitle(entry: Entry) {
  if ('description' in entry.payload) return entry.payload.description
  if ('text' in entry.payload) return entry.payload.text
  if ('code' in entry.payload) return `${metricLabels[entry.payload.code]}: ${formatValue(entry.payload.value)} ${formatUnit(entry.payload.unit)}`
  return `${categoryLabels[entry.payload.category]}: ${formatValue(entry.payload.score)}/5`
}

function payloadSummary(payload: EntryPayload) {
  if ('description' in payload) return `Mass: ${formatValue(payload.mass_g)} g; kcal: ${formatValue(payload.nutrients?.energy_kcal)}`
  if ('text' in payload) return payload.text
  if ('code' in payload) return `Value: ${formatValue(payload.value)} ${formatUnit(payload.unit)}`
  return `Score: ${formatValue(payload.score)} out of 5`
}


function formatUnit(unit: string | null | undefined) {
  if (!unit) return ''
  const labels: Record<string, string> = { count: 'steps', 'шагов': 'steps', 'шаги': 'steps', 'мин': 'min', 'уд/мин': 'bpm' }
  return labels[unit] ?? unit
}

function formatValue(value: number | string | null | undefined) {
  return value === null || value === undefined ? 'unknown' : String(value)
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export default DiaryPage
