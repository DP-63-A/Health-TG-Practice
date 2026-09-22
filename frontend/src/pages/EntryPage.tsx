import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type { Entry, EntryPayload } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { Badge, Button, Card, ErrorState, FormField, LoadingState } from '../components/ui'

export default function EntryPage() {
  const { id = '' } = useParams(); const navigate = useNavigate(); const { markSessionExpired } = useAuth()
  const [entry, setEntry] = useState<Entry | null>(null); const [payloadText, setPayloadText] = useState('')
  const [occurredAt, setOccurredAt] = useState(''); const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [message, setMessage] = useState(''); const [busy, setBusy] = useState(false)
  const [sourceFile, setSourceFile] = useState({ fileId: '', url: '', error: '' })
  const requestIdRef = useRef(0)
  const applyEntry = useCallback((value: Entry) => { setEntry(value); setPayloadText(JSON.stringify(value.payload, null, 2)); setOccurredAt(toLocalInput(value.occurred_at)); setState('ready') }, [])
  const load = useCallback(async (signal?: AbortSignal) => {
    const requestId = requestIdRef.current + 1
    requestIdRef.current = requestId
    setMessage('')
    setState('loading')
    try {
      const result = await entriesApi.get(id, signal)
      if (requestId !== requestIdRef.current) return
      applyEntry(result)
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (requestId !== requestIdRef.current) return
      handleError(cause, markSessionExpired, setMessage); setState('error')
    }
  }, [applyEntry, id, markSessionExpired])
  useEffect(() => {
    const controller = new AbortController()
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(controller.signal)
    return () => { controller.abort(); requestIdRef.current += 1 }
  }, [load])
  useEffect(() => {
    const fileId = entry?.source_ref.file_id
    if (!fileId) return
    const controller = new AbortController()
    let objectUrl = ''
    let disposed = false
    void entriesApi.downloadFile(fileId, controller.signal).then((url) => {
      if (disposed) {
        if (URL.revokeObjectURL && !url.startsWith('blob:fixture/')) URL.revokeObjectURL(url)
        return
      }
      objectUrl = url; setSourceFile({ fileId, url, error: '' })
    }).catch((cause) => {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (disposed) return
      setSourceFile({ fileId, url: '', error: cause instanceof Error ? cause.message : 'Не удалось загрузить исходный файл.' })
    })
    return () => { disposed = true; controller.abort(); if (objectUrl && URL.revokeObjectURL && !objectUrl.startsWith('blob:fixture/')) URL.revokeObjectURL(objectUrl) }
  }, [entry?.source_ref.file_id])
  async function save(event: FormEvent) {
    event.preventDefault(); if (!entry) return
    let payload: EntryPayload
    try { payload = JSON.parse(payloadText) as EntryPayload } catch { setMessage('Payload должен быть корректным JSON.'); return }
    setBusy(true); setMessage('')
    try { applyEntry(await entriesApi.patch(entry.id, { expected_revision: entry.revision, occurred_at: new Date(occurredAt).toISOString(), payload })); setMessage('Изменения сохранены.') }
    catch (cause) { await handleMutationError(cause) } finally { setBusy(false) }
  }
  async function confirm() { if (!entry) return; setBusy(true); try { applyEntry(await entriesApi.confirm(entry.id, { expected_revision: entry.revision, submission_id: entry.submission_id ?? `web_${entry.id}_${entry.revision}` })); setMessage('Запись подтверждена.') } catch (cause) { await handleMutationError(cause) } finally { setBusy(false) } }
  async function remove() { if (!entry || !window.confirm(entry.status === 'draft' ? 'Отменить черновик?' : 'Удалить запись?')) return; setBusy(true); try { if (entry.status === 'draft') await entriesApi.cancel(entry.id); else await entriesApi.delete(entry.id, entry.revision); navigate('/diary', { replace: true }) } catch (cause) { await handleMutationError(cause); setBusy(false) } }
  async function handleMutationError(cause: unknown) { if (cause instanceof ApiError && cause.status === 409) { try { applyEntry(await entriesApi.get(id)); setMessage('Запись была изменена в другом окне. Загружена актуальная версия; проверьте изменения повторно.'); return } catch { /* show original error */ } } handleError(cause, markSessionExpired, setMessage) }
  if (state === 'loading') return <Card><LoadingState title="Загрузка записи" message="Получаем текущую ревизию." /></Card>
  if (state === 'error' || !entry) return <Card><ErrorState title="Запись не найдена" message={message || 'Не удалось открыть запись.'} actionLabel="Повторить" onAction={() => { setState('loading'); setMessage(''); return load() }} /></Card>
  return <Card className="entry-detail">
    <Link className="back-link" to="/diary">← К дневнику</Link><div className="section-heading"><h2>Проверка записи</h2><Badge tone={entry.status === 'confirmed' ? 'success' : entry.status === 'draft' ? 'warning' : 'danger'}>Статус: {entry.status}</Badge></div>
    <dl className="entry-meta"><div><dt>Тип</dt><dd>{entry.type}</dd></div><div><dt>Источник</dt><dd>{entry.source_kind}</dd></div><div><dt>Ревизия</dt><dd>{entry.revision}</dd></div><div><dt>Обновлена</dt><dd>{formatDate(entry.updated_at)}</dd></div></dl>
    <section className="source-card" aria-label="Источник записи">
      <h3>Источник</h3>
      <p>{entry.source_ref.label ?? entry.source_kind}</p>
      <p>Дата записи: {formatDate(entry.occurred_at)}</p>
      {entry.source_ref.telegram_message_id && <p>Telegram message: {entry.source_ref.telegram_message_id}</p>}
      {entry.source_ref.file_id && sourceFile.fileId !== entry.source_ref.file_id && <p role="status">Загружаем исходный файл через защищённый API…</p>}
      {entry.source_ref.file_id && sourceFile.fileId === entry.source_ref.file_id && sourceFile.url && <a className="source-link" href={sourceFile.url} target="_blank" rel="noreferrer">Открыть исходное изображение</a>}
      {entry.source_ref.file_id && sourceFile.fileId === entry.source_ref.file_id && sourceFile.error && <p role="alert">{sourceFile.error}</p>}
    </section>
    {entry.status === 'draft' && <p className="draft-note">Черновик должен направляться в форму FE1-04 после появления отдельного маршрута формы.</p>}
    <section className="source-card" aria-label="Происхождение полей">
      <h3>Происхождение полей</h3>
      <ul>{Object.entries(entry.field_origins).map(([field, origin]) => <li key={field}>{field}: {origin}</li>)}</ul>
    </section>
    <form className="entry-form" onSubmit={save}><FormField label="Дата и время"><input type="datetime-local" required value={occurredAt} onChange={(e) => setOccurredAt(e.target.value)} /></FormField><FormField label="Данные записи (JSON)"><textarea rows={12} required value={payloadText} onChange={(e) => setPayloadText(e.target.value)} /></FormField>
      {message && <p role="status" className={message.includes('изменена') ? 'conflict-message' : ''}>{message}</p>}
      <div className="form-actions"><Button disabled={busy} type="submit">Сохранить</Button>{entry.status === 'draft' && <Button disabled={busy} onClick={() => void confirm()} variant="secondary">Подтвердить</Button>}{(entry.status === 'draft' || entry.status === 'confirmed') && <Button disabled={busy} onClick={() => void remove()} variant="danger">{entry.status === 'draft' ? 'Отменить' : 'Удалить'}</Button>}</div>
    </form>
  </Card>
}
function handleError(cause: unknown, expire: () => void, setMessage: (value: string) => void) { if (cause instanceof ApiError && cause.status === 401) { expire(); return } setMessage(cause instanceof Error ? cause.message : 'Произошла ошибка.') }
function toLocalInput(value: string) { const date = new Date(value); const offset = date.getTimezoneOffset() * 60_000; return new Date(date.getTime() - offset).toISOString().slice(0, 16) }
function formatDate(value: string) { return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
