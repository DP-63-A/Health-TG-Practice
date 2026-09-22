import { useCallback, useEffect, useState, type FormEvent } from 'react'
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
  const applyEntry = useCallback((value: Entry) => { setEntry(value); setPayloadText(JSON.stringify(value.payload, null, 2)); setOccurredAt(toLocalInput(value.occurred_at)); setState('ready') }, [])
  const load = useCallback(async () => { try { applyEntry(await entriesApi.get(id)) } catch (cause) { handleError(cause, markSessionExpired, setMessage); setState('error') } }, [applyEntry, id, markSessionExpired])
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load() }, [load])
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
    {entry.source_ref.label && <p>Источник: {entry.source_ref.label}</p>}{entry.source_ref.file_id && <a className="source-link" href={entriesApi.fileUrl(entry.source_ref.file_id)} target="_blank" rel="noreferrer">Открыть исходное изображение</a>}
    <form className="entry-form" onSubmit={save}><FormField label="Дата и время"><input type="datetime-local" required value={occurredAt} onChange={(e) => setOccurredAt(e.target.value)} /></FormField><FormField label="Данные записи (JSON)"><textarea rows={12} required value={payloadText} onChange={(e) => setPayloadText(e.target.value)} /></FormField>
      {message && <p role="status" className={message.includes('изменена') ? 'conflict-message' : ''}>{message}</p>}
      <div className="form-actions"><Button disabled={busy} type="submit">Сохранить</Button>{entry.status === 'draft' && <Button disabled={busy} onClick={() => void confirm()} variant="secondary">Подтвердить</Button>}{(entry.status === 'draft' || entry.status === 'confirmed') && <Button disabled={busy} onClick={() => void remove()} variant="danger">{entry.status === 'draft' ? 'Отменить' : 'Удалить'}</Button>}</div>
    </form>
  </Card>
}
function handleError(cause: unknown, expire: () => void, setMessage: (value: string) => void) { if (cause instanceof ApiError && cause.status === 401) { expire(); return } setMessage(cause instanceof Error ? cause.message : 'Произошла ошибка.') }
function toLocalInput(value: string) { const date = new Date(value); const offset = date.getTimezoneOffset() * 60_000; return new Date(date.getTime() - offset).toISOString().slice(0, 16) }
function formatDate(value: string) { return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
