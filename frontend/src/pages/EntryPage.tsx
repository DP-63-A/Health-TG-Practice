import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type RefObject } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { entriesApi } from '../api/entries'
import type {
  CheckinPayload,
  Entry,
  EntryPayload,
  EntryType,
  FieldOrigin,
  MealPayload,
  MetricsPayload,
  NotePayload,
} from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { Badge, Button, Card, ErrorState, FormField, LoadingState } from '../components/ui'
import { useRefresh } from '../refresh/RefreshProvider'

type BusyAction = 'save' | 'confirm' | 'cancel' | 'delete'
type FieldErrors = Record<string, string>

interface EntryFormState {
  occurredAt: string
  description: string
  mass_g: string
  energy_kcal: string
  protein_g: string
  fat_g: string
  carbs_g: string
  nutrients_basis: MealPayload['nutrients_basis'] | ''
  metric_code: MetricsPayload['code'] | ''
  metric_value: string
  metric_unit: string
  metric_local_date: string
  metric_local_time: string
  metric_qualifier: NonNullable<MetricsPayload['qualifier']> | ''
  checkin_category: CheckinPayload['category'] | ''
  checkin_score: string
  note_text: string
}

const typeLabels: Record<EntryType, string> = {
  meal: 'Питание',
  metrics: 'Метрика',
  checkin: 'Оценка',
  note: 'Заметка',
}

const originLabels: Record<FieldOrigin, string> = {
  reported: 'сообщено пользователем',
  extracted: 'извлечено',
  estimated: 'оценочное',
  computed: 'рассчитано',
}

const checkinLabels: Record<CheckinPayload['category'], string> = {
  sleep_quality: 'Качество сна',
  digestion_comfort: 'Комфорт пищеварения',
  wellbeing: 'Самочувствие',
  mood: 'Настроение',
}

const metricLabels: Record<MetricsPayload['code'], string> = {
  steps: 'Шаги',
  sleep_duration_min: 'Сон',
  heart_rate: 'Пульс',
}

const emptyForm: EntryFormState = {
  occurredAt: '',
  description: '',
  mass_g: '',
  energy_kcal: '',
  protein_g: '',
  fat_g: '',
  carbs_g: '',
  nutrients_basis: '',
  metric_code: '',
  metric_value: '',
  metric_unit: '',
  metric_local_date: '',
  metric_local_time: '',
  metric_qualifier: '',
  checkin_category: '',
  checkin_score: '',
  note_text: '',
}

export default function EntryPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { markSessionExpired, state: authState } = useAuth()
  const { refreshReason, refreshVersion, requestRefresh } = useRefresh()
  const timezone = authState.status === 'authenticated'
    ? authState.user.timezone
    : 'Europe/Warsaw'

  const [entry, setEntry] = useState<Entry | null>(null)
  const [form, setForm] = useState<EntryFormState>(emptyForm)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [message, setMessage] = useState('')
  const [busyAction, setBusyAction] = useState<BusyAction | null>(null)
  const [sourceFile, setSourceFile] = useState({ fileId: '', url: '', error: '' })
  const [conflictEntry, setConflictEntry] = useState<Entry | null>(null)
  const [conflictActive, setConflictActive] = useState(false)
  const [freshError, setFreshError] = useState('')
  const [replaceRequested, setReplaceRequested] = useState(false)
  const [acceptingFresh, setAcceptingFresh] = useState(false)
  const [deleteRequested, setDeleteRequested] = useState(false)
  const requestIdRef = useRef(0)
  const currentEntryRef = useRef<Entry | null>(null)
  const latestSnapshotRef = useRef<Entry | null>(null)
  const freshRequestRef = useRef<AbortController | null>(null)
  const acceptLockRef = useRef(false)
  const mutationLockRef = useRef(false)
  const submissionRef = useRef<{ entryId: string; value: string } | null>(null)
  const dateInputRef = useRef<HTMLInputElement>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const metricDateInputRef = useRef<HTMLInputElement>(null)
  const activeIdRef = useRef(id)
  const mountedRef = useRef(true)

  useEffect(() => {
    if (busyAction === null && !acceptingFresh && Object.keys(fieldErrors).length > 0) {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    }
  }, [acceptingFresh, busyAction, fieldErrors])

  useEffect(() => {
    activeIdRef.current = id
    return () => { freshRequestRef.current?.abort() }
  }, [id])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      freshRequestRef.current?.abort()
    }
  }, [])

  const applyEntry = useCallback((value: Entry) => {
    currentEntryRef.current = value
    latestSnapshotRef.current = null
    setEntry(value)
    setForm(createForm(value, timezone))
    setFieldErrors({})
    setConflictEntry(null)
    setConflictActive(false)
    setFreshError('')
    setReplaceRequested(false)
    setDeleteRequested(false)
    setState('ready')
  }, [timezone])

  const readLatest = useCallback(async (entryId: string, reason: 'conflict' | 'refresh') => {
    freshRequestRef.current?.abort()
    const controller = new AbortController()
    freshRequestRef.current = controller
    setFreshError('')
    try {
      const fresh = await entriesApi.get(entryId, controller.signal)
      if (controller.signal.aborted || !mountedRef.current || activeIdRef.current !== entryId) return
      const current = currentEntryRef.current
      if (current && fresh.revision <= current.revision && fresh.status === current.status) {
        if (reason === 'conflict') setFreshError('Сервер пока не вернул новую версию. Повторите чтение.')
        return
      }
      const previous = latestSnapshotRef.current
      if (previous?.id === entryId && fresh.revision < previous.revision) return
      if (!previous || previous.id !== entryId || JSON.stringify(previous) !== JSON.stringify(fresh)) setReplaceRequested(false)
      latestSnapshotRef.current = fresh
      setConflictEntry(fresh)
      setConflictActive(true)
      if (reason === 'refresh') setMessage('На сервере появилась новая версия записи. Ваш ввод сохранён; сравните версии перед продолжением.')
    } catch (cause) {
      if (controller.signal.aborted || !mountedRef.current || activeIdRef.current !== entryId) return
      if (cause instanceof ApiError && cause.status === 401) {
        markSessionExpired()
        return
      }
      setFreshError(cause instanceof Error ? cause.message : 'Не удалось перечитать запись.')
    } finally {
      if (freshRequestRef.current === controller) freshRequestRef.current = null
    }
  }, [markSessionExpired])

  const load = useCallback(async (signal?: AbortSignal) => {
    if (authState.status !== 'authenticated') return

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
      handleError(cause, markSessionExpired, setMessage)
      setState('error')
    }
  }, [applyEntry, authState.status, id, markSessionExpired])

  useEffect(() => {
    const controller = new AbortController()
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(controller.signal)
    return () => {
      controller.abort()
      requestIdRef.current += 1
    }
  }, [load])

  useEffect(() => {
    if (refreshVersion === 0 || refreshReason === 'mutation') return
    const current = currentEntryRef.current
    if (current?.id === id) void readLatest(id, 'refresh')
  }, [id, readLatest, refreshReason, refreshVersion])

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
      objectUrl = url
      setSourceFile({ fileId, url, error: '' })
    }).catch((cause) => {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      if (disposed) return
      setSourceFile({
        fileId,
        url: '',
        error: cause instanceof Error ? cause.message : 'Не удалось загрузить исходный файл.',
      })
    })

    return () => {
      disposed = true
      controller.abort()
      if (objectUrl && URL.revokeObjectURL && !objectUrl.startsWith('blob:fixture/')) {
        URL.revokeObjectURL(objectUrl)
      }
    }
  }, [entry?.source_ref.file_id])

  const draftBody = useMemo(() => {
    if (!entry) return null
    const result = buildPatchBody(entry, form, timezone)
    return result.ok ? result.body : null
  }, [entry, form, timezone])

  const hasLocalChanges = useMemo(() => {
    if (!entry || !draftBody) return false
    return Boolean(draftBody.payload || draftBody.occurred_at)
  }, [draftBody, entry])

  function updateForm(patch: Partial<EntryFormState>) {
    setForm((value) => ({ ...value, ...patch }))
    setFieldErrors({})
    if (!conflictActive) setMessage('')
  }

  async function save(event?: FormEvent) {
    event?.preventDefault()
    if (!entry || (entry.status !== 'draft' && entry.status !== 'confirmed') || conflictActive || busyAction || mutationLockRef.current) return null
    mutationLockRef.current = true
    try {
      return await saveCurrentEntry(entry, false)
    } finally {
      mutationLockRef.current = false
    }
  }

  async function saveCurrentEntry(currentEntry: Entry, silent: boolean) {
    const result = buildPatchBody(currentEntry, form, timezone)
    if (!result.ok) {
      setFieldErrors(result.errors)
      setMessage('Проверьте поля формы.')
      return null
    }

    setBusyAction('save')
    setMessage('')
    setFieldErrors({})

    try {
      const updated = await entriesApi.patch(currentEntry.id, result.body)
      if (mountedRef.current && activeIdRef.current === currentEntry.id) applyEntry(updated)
      requestRefresh('mutation')
      if (!silent && mountedRef.current && activeIdRef.current === currentEntry.id) setMessage('Изменения сохранены.')
      return updated
    } catch (cause) {
      await handleMutationError(cause, currentEntry.id)
      return null
    } finally {
      if (mountedRef.current) setBusyAction(null)
    }
  }

  async function confirmDraft() {
    if (!entry || conflictActive || busyAction || mutationLockRef.current || entry.status !== 'draft') return
    mutationLockRef.current = true

    let currentEntry = entry
    try {
      const checked = buildPatchBody(entry, form, timezone)
      if (!checked.ok) {
        setFieldErrors(checked.errors)
        setMessage('Проверьте поля формы.')
        return
      }
      if (entry.type === 'metrics' && (!form.metric_unit.trim() || !form.metric_local_date)) {
        setFieldErrors({
          ...(!form.metric_unit.trim() ? { 'payload.unit': 'Укажите единицу перед подтверждением.' } : {}),
          ...(!form.metric_local_date ? { 'payload.local_date': 'Укажите дату перед подтверждением.' } : {}),
        })
        setMessage('Проверьте поля формы.')
        return
      }
      if (hasLocalChanges) {
        const saved = await saveCurrentEntry(entry, true)
        if (!saved) return
        currentEntry = saved
      }
      if (!mountedRef.current || activeIdRef.current !== currentEntry.id) return

      setBusyAction('confirm')
      setMessage('')
      setFieldErrors({})

      const updated = await entriesApi.confirm(currentEntry.id, {
        expected_revision: currentEntry.revision,
        submission_id: getSubmissionId(currentEntry),
      })
      if (mountedRef.current && activeIdRef.current === currentEntry.id) applyEntry(updated)
      requestRefresh('mutation')
      if (mountedRef.current && activeIdRef.current === currentEntry.id) setMessage('Запись подтверждена.')
    } catch (cause) {
      await handleMutationError(cause, currentEntry.id)
    } finally {
      if (mountedRef.current) setBusyAction(null)
      mutationLockRef.current = false
    }
  }

  async function cancelDraft() {
    if (!entry || conflictActive || busyAction || mutationLockRef.current || entry.status !== 'draft') return
    mutationLockRef.current = true

    setBusyAction('cancel')
    setMessage('')
    setFieldErrors({})

    try {
      await entriesApi.cancel(entry.id, entry.revision)
      requestRefresh('mutation')
      if (mountedRef.current && activeIdRef.current === entry.id) navigate('/diary', { replace: true })
    } catch (cause) {
      await handleMutationError(cause, entry.id)
    } finally {
      if (mountedRef.current) setBusyAction(null)
      mutationLockRef.current = false
    }
  }

  async function removeConfirmed() {
    if (!entry || entry.status !== 'confirmed' || !deleteRequested || conflictActive || busyAction || mutationLockRef.current) return
    mutationLockRef.current = true
    setBusyAction('delete')
    setMessage('')
    try {
      const removed = await entriesApi.delete(entry.id, entry.revision)
      if (mountedRef.current && activeIdRef.current === entry.id) {
        applyEntry(removed)
        setMessage('Запись убрана из дневника.')
      }
      requestRefresh('mutation')
    } catch (cause) {
      await handleMutationError(cause, entry.id)
    } finally {
      if (mountedRef.current) setBusyAction(null)
      mutationLockRef.current = false
    }
  }

  async function acceptFreshSnapshot() {
    if (!conflictEntry || !replaceRequested || acceptLockRef.current || busyAction) return
    const snapshot = conflictEntry
    acceptLockRef.current = true
    setAcceptingFresh(true)
    setFreshError('')
    freshRequestRef.current?.abort()
    const controller = new AbortController()
    freshRequestRef.current = controller
    try {
      const verified = await entriesApi.get(snapshot.id, controller.signal)
      if (controller.signal.aborted || !mountedRef.current || activeIdRef.current !== snapshot.id) return
      const newer = latestSnapshotRef.current
      if (verified.revision < snapshot.revision || (newer && newer.revision > verified.revision)) {
        setFreshError('Сервер пока не вернул самую новую версию. Повторите чтение.')
        setReplaceRequested(false)
        return
      }
      if (JSON.stringify(verified) !== JSON.stringify(snapshot)) {
        latestSnapshotRef.current = verified
        setConflictEntry(verified)
        setReplaceRequested(false)
        setMessage('Серверная версия снова изменилась. Сравните её перед заменой ввода.')
        return
      }
      applyEntry(verified)
      setMessage('Серверная версия загружена. Проверьте запись перед новым действием.')
    } catch (cause) {
      if (controller.signal.aborted || !mountedRef.current || activeIdRef.current !== snapshot.id) return
      if (cause instanceof ApiError && cause.status === 401) markSessionExpired()
      else setFreshError(cause instanceof Error ? cause.message : 'Не удалось проверить серверную версию.')
      setReplaceRequested(false)
    } finally {
      if (freshRequestRef.current === controller) freshRequestRef.current = null
      if (mountedRef.current) setAcceptingFresh(false)
      acceptLockRef.current = false
    }
  }

  async function handleMutationError(cause: unknown, entryId: string) {
    if (!mountedRef.current || activeIdRef.current !== entryId) return
    if (cause instanceof ApiError && cause.status === 422) {
      setFieldErrors(toFieldErrors(cause.field_errors))
      setMessage(cause.message)
      return
    }

    if (cause instanceof ApiError && cause.status === 409) {
      setConflictActive(true)
      setConflictEntry(null)
      latestSnapshotRef.current = null
      setReplaceRequested(false)
      setDeleteRequested(false)
      setMessage(cause.code === 'INVALID_STATUS_TRANSITION'
        ? 'Действие больше недоступно: статус записи изменился. Ваш ввод сохранён; проверьте серверную версию.'
        : 'Конфликт версий: запись изменилась на сервере. Ваш ввод сохранён; сравните версии перед повтором.')
      await readLatest(entryId, 'conflict')
      return
    }

    handleError(cause, markSessionExpired, setMessage)
  }

  function getSubmissionId(currentEntry: Entry) {
    if (currentEntry.submission_id) return currentEntry.submission_id
    if (submissionRef.current?.entryId === currentEntry.id) return submissionRef.current.value

    const value = `web_${currentEntry.id}_${newIdPart()}`
    submissionRef.current = { entryId: currentEntry.id, value }
    return value
  }

  if (state === 'loading') {
    return (
      <Card>
        <LoadingState title="Загрузка записи" message="Получаем текущую ревизию." />
      </Card>
    )
  }

  if (state === 'error' || !entry) {
    return (
      <Card>
        <ErrorState
          title="Запись не найдена"
          message={message || 'Не удалось открыть запись.'}
          actionLabel="Повторить"
          onAction={() => {
            setState('loading')
            setMessage('')
            return load()
          }}
        />
      </Card>
    )
  }

  const isBusy = busyAction !== null || acceptingFresh
  const canEdit = entry.status === 'draft' || entry.status === 'confirmed'
  const actionsBlocked = isBusy || conflictActive

  return (
    <Card className="entry-detail">
      <Link className="back-link" to="/diary">← К дневнику</Link>
      <div className="section-heading">
        <h2>Проверка записи</h2>
        <Badge tone={entry.status === 'confirmed' ? 'success' : entry.status === 'draft' ? 'warning' : 'danger'}>
          Статус: {entry.status}
        </Badge>
      </div>

      <dl className="entry-meta">
        <div><dt>Тип</dt><dd>{typeLabels[entry.type]}</dd></div>
        <div><dt>Источник</dt><dd>{entry.source_kind}</dd></div>
        <div><dt>Ревизия</dt><dd>{entry.revision}</dd></div>
        <div><dt>Обновлена</dt><dd>{formatDate(entry.updated_at, timezone)}</dd></div>
      </dl>

      <section className="source-card" aria-label="Источник записи">
        <h3>Источник</h3>
        <p>{entry.source_ref.label ?? entry.source_kind}</p>
        <p>{isMetricEntry(entry) ? 'Время сообщения итога' : 'Дата записи'}: {formatNullableDate(entry.occurred_at, timezone)}</p>
        {isMetricEntry(entry) && <p>{metricDateLabel(entry)}: {(entry.payload as MetricsPayload).local_date || 'неизвестно'}</p>}
        {entry.source_ref.telegram_message_id && <p>Telegram message: {entry.source_ref.telegram_message_id}</p>}
        {entry.source_ref.file_id && (sourceFile.fileId !== entry.source_ref.file_id || (!sourceFile.url && !sourceFile.error)) && (
          <p role="status">Загружаем исходный файл через защищённый API...</p>
        )}
        {entry.source_ref.file_id && sourceFile.fileId === entry.source_ref.file_id && sourceFile.url && (
          <a className="source-link" href={sourceFile.url} target="_blank" rel="noreferrer">
            Открыть исходное изображение
          </a>
        )}
        {entry.source_ref.file_id && sourceFile.fileId === entry.source_ref.file_id && sourceFile.error && (
          <p role="alert">{sourceFile.error}</p>
        )}
      </section>

      <section className="source-card" aria-label="Происхождение полей">
        <h3>Происхождение полей</h3>
        {Object.keys(entry.field_origins).length === 0 ? (
          <p>Происхождение полей не указано.</p>
        ) : (
          <ul>
            {Object.entries(entry.field_origins).map(([field, origin]) => (
              <li key={field}>{field}: {originLabels[origin]}{origin === 'estimated' ? ' (estimated)' : ''}</li>
            ))}
          </ul>
        )}
      </section>

      {conflictActive && (
        <section className="source-card conflict-panel" aria-label="Свежая серверная версия">
          <h3>Свежая серверная версия</h3>
          {conflictEntry ? (
            <>
              <p>Статус: {conflictEntry.status}. Ревизия: {conflictEntry.revision}. Локальный ввод ниже не заменён.</p>
              <pre tabIndex={0} aria-label="Данные свежей серверной версии">{JSON.stringify(conflictEntry.payload, null, 2)}</pre>
              {!replaceRequested ? (
                <Button onClick={() => setReplaceRequested(true)} variant="secondary">Использовать серверную версию</Button>
              ) : (
                <div>
                  <p>Несохранённый ввод в форме будет заменён серверной версией.</p>
                  <div className="form-actions">
                    <Button disabled={acceptingFresh} isLoading={acceptingFresh} onClick={() => void acceptFreshSnapshot()} variant="danger">Да, заменить ввод</Button>
                    <Button disabled={acceptingFresh} onClick={() => setReplaceRequested(false)} variant="secondary">Оставить мой ввод</Button>
                  </div>
                </div>
              )}
            </>
          ) : <p>Свежую версию пока не удалось получить. Ваш ввод остаётся в форме.</p>}
          {freshError && <p role="alert">{freshError}</p>}
          <Button disabled={acceptingFresh} onClick={() => void readLatest(entry.id, 'conflict')} variant="secondary">Перечитать серверную версию</Button>
        </section>
      )}

      <form ref={formRef} className="entry-form" aria-label="Редактирование записи" onSubmit={(event) => void save(event)} noValidate>
        <fieldset className="entry-edit-fields" disabled={isBusy || !canEdit}>
        <FormField label={isMetricEntry(entry) ? 'Время сообщения итога' : 'Дата и время'} hint={isMetricEntry(entry) ? `Часовой пояс: ${timezone}. Сохраняется исходное время сообщения. ` : `Timezone: ${timezone}. Пустое поле не заменяется текущим временем.`} error={errorFor(fieldErrors, 'occurred_at')}>
          <input
            readOnly={isMetricEntry(entry)}
            ref={dateInputRef}
            type="datetime-local"
            value={form.occurredAt}
            onChange={(event) => updateForm({ occurredAt: event.target.value })}
          />
        </FormField>

        {renderPayloadForm(entry, form, fieldErrors, updateForm, metricDateInputRef)}
        </fieldset>

        {message && (
          <p role="status" className={message.includes('Конфликт') ? 'conflict-message' : undefined}>
            {message}
          </p>
        )}

        <div className="form-actions">
          {canEdit && (
            <Button disabled={actionsBlocked} isLoading={busyAction === 'save'} type="submit">
              Сохранить
            </Button>
          )}
          {entry.status === 'draft' && (
            <>
              <Button disabled={isBusy} onClick={() => (isMetricEntry(entry) ? metricDateInputRef : dateInputRef).current?.focus()} variant="secondary">
                Изменить
              </Button>
              <Button disabled={actionsBlocked} isLoading={busyAction === 'confirm'} onClick={() => void confirmDraft()} variant="secondary">
                Подтвердить
              </Button>
              <Button disabled={actionsBlocked} isLoading={busyAction === 'cancel'} onClick={() => void cancelDraft()} variant="danger">
                Не сохранять
              </Button>
            </>
          )}
          {entry.status === 'confirmed' && !deleteRequested && (
            <Button disabled={actionsBlocked} onClick={() => setDeleteRequested(true)} variant="danger">
              Убрать из дневника
            </Button>
          )}
        </div>
        {entry.status === 'confirmed' && deleteRequested && (
          <div className="delete-confirmation" role="group" aria-label="Подтверждение удаления">
            <p>Запись исчезнет из дневника и аналитики.</p>
            <div className="form-actions">
              <Button disabled={actionsBlocked} isLoading={busyAction === 'delete'} onClick={() => void removeConfirmed()} variant="danger">Да, убрать</Button>
              <Button disabled={isBusy} onClick={() => setDeleteRequested(false)} variant="secondary">Оставить запись</Button>
            </div>
          </div>
        )}
      </form>
    </Card>
  )
}

function renderPayloadForm(
  entry: Entry,
  form: EntryFormState,
  errors: FieldErrors,
  updateForm: (patch: Partial<EntryFormState>) => void,
  metricDateInputRef: RefObject<HTMLInputElement | null>,
) {
  if (entry.type === 'meal') {
    return (
      <fieldset className="entry-fieldset">
        <legend>Питание</legend>
        <FormField label="Описание" hint={originHint(entry, 'description')} error={errorFor(errors, 'payload.description')}>
          <input value={form.description} onChange={(event) => updateForm({ description: event.target.value })} />
        </FormField>
        <NumberField entry={entry} errors={errors} field="mass_g" label="Масса" unit="г" value={form.mass_g} onChange={(value) => updateForm({ mass_g: value })} />
        <FormField label="Основа нутриентов" hint={originHint(entry, 'nutrients_basis')} error={errorFor(errors, 'payload.nutrients_basis')}>
          <select value={form.nutrients_basis} onChange={(event) => updateForm({ nutrients_basis: event.target.value as EntryFormState['nutrients_basis'] })}>
            <option value="">Неизвестно</option>
            <option value="per_100g">На 100 г</option>
            <option value="per_serving">На порцию</option>
            <option value="unknown">Unknown</option>
          </select>
        </FormField>
        <div className="entry-form-grid">
          <NumberField entry={entry} errors={errors} field="nutrients.energy_kcal" label="Ккал" unit="kcal" value={form.energy_kcal} onChange={(value) => updateForm({ energy_kcal: value })} />
          <NumberField entry={entry} errors={errors} field="nutrients.protein_g" label="Белки" unit="г" value={form.protein_g} onChange={(value) => updateForm({ protein_g: value })} />
          <NumberField entry={entry} errors={errors} field="nutrients.fat_g" label="Жиры" unit="г" value={form.fat_g} onChange={(value) => updateForm({ fat_g: value })} />
          <NumberField entry={entry} errors={errors} field="nutrients.carbs_g" label="Углеводы" unit="г" value={form.carbs_g} onChange={(value) => updateForm({ carbs_g: value })} />
        </div>
      </fieldset>
    )
  }

  if (entry.type === 'metrics') {
    return (
      <fieldset className="entry-fieldset">
        <legend>Метрика</legend>
        <FormField label="Показатель" hint={originHint(entry, 'code')} error={errorFor(errors, 'payload.code')}>
          <select disabled={isMetricEntry(entry)} value={form.metric_code} onChange={(event) => updateForm({ metric_code: event.target.value as EntryFormState['metric_code'] })}>
            <option value="">Выберите показатель</option>
            {Object.entries(metricLabels).map(([value, label]) => <option disabled={value === 'steps' && !isMetricEntry(entry)} key={value} value={value}>{label}</option>)}
          </select>
        </FormField>
        <NumberField entry={entry} errors={errors} field="value" label="Значение" unit={form.metric_unit || 'ед.'} nullable={false} value={form.metric_value} onChange={(value) => updateForm({ metric_value: value })} />
        <FormField label="Единица" hint={`${originHint(entry, 'unit')} Пустое поле = unknown для draft.`} error={errorFor(errors, 'payload.unit')}>
          <input maxLength={32} value={form.metric_unit} onChange={(event) => updateForm({ metric_unit: event.target.value })} />
          <UnknownMark value={form.metric_unit} />
        </FormField>
        <div className="entry-form-grid">
          <FormField label={isMetricEntry(entry) ? metricDateLabel(entry) : 'Локальная дата'} hint={`${originHint(entry, 'local_date')} Пустое поле = unknown для draft.`} error={errorFor(errors, 'payload.local_date')}>
            <input ref={metricDateInputRef} type="date" value={form.metric_local_date} onChange={(event) => updateForm({ metric_local_date: event.target.value })} />
            <UnknownMark value={form.metric_local_date} />
          </FormField>
          {(entry.payload as MetricsPayload).code !== 'steps' && <FormField label="Локальное время" hint={`${originHint(entry, 'local_time')} Пустое поле = unknown.`} error={errorFor(errors, 'payload.local_time')}>
            <input type="time" value={form.metric_local_time} onChange={(event) => updateForm({ metric_local_time: event.target.value })} />
            <UnknownMark value={form.metric_local_time} />
          </FormField>}
        </div>
        <FormField label="Уточнение пульса" hint={originHint(entry, 'qualifier')} error={errorFor(errors, 'payload.qualifier')}>
          <select value={form.metric_qualifier} onChange={(event) => updateForm({ metric_qualifier: event.target.value as EntryFormState['metric_qualifier'] })}>
            <option value="">Unknown</option>
            <option value="instant">Instant</option>
            <option value="resting">Resting</option>
          </select>
        </FormField>
      </fieldset>
    )
  }

  if (entry.type === 'checkin') {
    return (
      <fieldset className="entry-fieldset">
        <legend>Оценка</legend>
        <FormField label="Категория" hint={originHint(entry, 'category')} error={errorFor(errors, 'payload.category')}>
          <select value={form.checkin_category} onChange={(event) => updateForm({ checkin_category: event.target.value as EntryFormState['checkin_category'] })}>
            <option value="">Выберите категорию</option>
            {Object.entries(checkinLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </FormField>
        <FormField label="Оценка" hint={`${originHint(entry, 'score')} Значение 1-5 по контракту.`} error={errorFor(errors, 'payload.score')}>
          <input inputMode="numeric" value={form.checkin_score} onChange={(event) => updateForm({ checkin_score: event.target.value })} />
        </FormField>
      </fieldset>
    )
  }

  return (
    <fieldset className="entry-fieldset">
      <legend>Заметка</legend>
      <FormField label="Текст" hint={originHint(entry, 'text')} error={errorFor(errors, 'payload.text')}>
        <textarea rows={6} value={form.note_text} onChange={(event) => updateForm({ note_text: event.target.value })} />
      </FormField>
    </fieldset>
  )
}

function NumberField({
  entry,
  errors,
  field,
  label,
  nullable = true,
  onChange,
  unit,
  value,
}: {
  entry: Entry
  errors: FieldErrors
  field: string
  label: string
  nullable?: boolean
  onChange: (value: string) => void
  unit: string
  value: string
}) {
  const path = `payload.${field}`
  return (
    <FormField label={label} hint={`${unit}. ${originHint(entry, field)}${nullable ? ' Пустое поле = unknown.' : ''}`} error={errorFor(errors, path)}>
      <input
        inputMode="decimal"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
      {nullable && <UnknownMark value={value} />}
    </FormField>
  )
}

function UnknownMark({ value }: { value: string }) {
  if (value !== '') return null
  return <span className="unknown-value">Неизвестно</span>
}

function createForm(entry: Entry, timezone: string): EntryFormState {
  const form = { ...emptyForm, occurredAt: toZonedLocalInput(entry.occurred_at, timezone) }

  if (entry.type === 'meal') {
    const payload = entry.payload as MealPayload
    form.description = payload.description ?? ''
    form.mass_g = toInputValue(payload.mass_g)
    form.energy_kcal = toInputValue(payload.nutrients?.energy_kcal)
    form.protein_g = toInputValue(payload.nutrients?.protein_g)
    form.fat_g = toInputValue(payload.nutrients?.fat_g)
    form.carbs_g = toInputValue(payload.nutrients?.carbs_g)
    form.nutrients_basis = payload.nutrients_basis ?? ''
  }

  if (entry.type === 'metrics') {
    const payload = entry.payload as MetricsPayload
    form.metric_code = payload.code ?? ''
    form.metric_value = toInputValue(payload.value)
    form.metric_unit = payload.unit ?? ''
    form.metric_local_date = payload.local_date ?? ''
    form.metric_local_time = payload.local_time ?? ''
    form.metric_qualifier = payload.qualifier ?? ''
  }

  if (entry.type === 'checkin') {
    const payload = entry.payload as CheckinPayload
    form.checkin_category = payload.category ?? ''
    form.checkin_score = toInputValue(payload.score)
  }

  if (entry.type === 'note') {
    const payload = entry.payload as NotePayload
    form.note_text = payload.text ?? ''
  }

  return form
}

function isMetricEntry(entry: Entry) {
  return entry.type === 'metrics'
}

function buildPatchBody(entry: Entry, form: EntryFormState, timezone: string) {
  const errors: FieldErrors = {}
  const payload = buildPayload(entry.type, form, errors)
  const original = createForm(entry, timezone)
  const changedPayload = changedFields(entry.type, form, original, payload)
  if (entry.type === 'meal' && form.nutrients_basis === '' && original.nutrients_basis !== '') {
    errors['payload.nutrients_basis'] = 'Укажите основу или выберите Unknown.'
  }
  if (entry.type === 'metrics' && entry.status === 'confirmed') {
    if (!form.metric_unit.trim()) errors['payload.unit'] = 'Единица обязательна для подтверждённой метрики.'
    if (!form.metric_local_date) errors['payload.local_date'] = 'Дата обязательна для подтверждённой метрики.'
  }
  const dateChanged = !isMetricEntry(entry) && form.occurredAt !== original.occurredAt
  const occurredAt = dateChanged && form.occurredAt ? localInputToUtc(form.occurredAt, timezone) : undefined

  if (dateChanged && !occurredAt) {
    errors.occurred_at = form.occurredAt
      ? 'Дата или время не существует либо неоднозначно в часовом поясе пользователя.'
      : 'Контракт не позволяет очистить известную дату.'
  }

  if (Object.keys(errors).length > 0) {
    return { ok: false as const, errors }
  }

  return {
    ok: true as const,
    body: compact({
      expected_revision: entry.revision,
      occurred_at: occurredAt && !isSameInstant(occurredAt, entry.occurred_at) ? occurredAt : undefined,
      payload: Object.keys(changedPayload).length ? changedPayload : undefined,
    }),
  }
}

function changedFields(type: EntryType, form: EntryFormState, original: EntryFormState, payload: EntryPayload) {
  const changed: Record<string, unknown> = {}
  const copy = (input: keyof EntryFormState, key: string, value: unknown) => {
    if (form[input] !== original[input]) changed[key] = value
  }

  if (type === 'meal') {
    const meal = payload as MealPayload
    copy('description', 'description', meal.description)
    copy('mass_g', 'mass_g', meal.mass_g)
    copy('nutrients_basis', 'nutrients_basis', meal.nutrients_basis)
    const nutrients: Record<string, unknown> = {}
    const fields = [
      ['energy_kcal', 'energy_kcal'], ['protein_g', 'protein_g'],
      ['fat_g', 'fat_g'], ['carbs_g', 'carbs_g'],
    ] as const
    for (const [input, key] of fields) {
      if (form[input] !== original[input]) nutrients[key] = meal.nutrients?.[key]
    }
    if (Object.keys(nutrients).length) changed.nutrients = nutrients
  } else if (type === 'metrics') {
    const metrics = payload as MetricsPayload
    copy('metric_code', 'code', metrics.code)
    copy('metric_value', 'value', metrics.value)
    copy('metric_unit', 'unit', metrics.unit)
    copy('metric_local_date', 'local_date', metrics.local_date)
    copy('metric_local_time', 'local_time', metrics.local_time)
    copy('metric_qualifier', 'qualifier', metrics.qualifier)
  } else if (type === 'checkin') {
    const checkin = payload as CheckinPayload
    copy('checkin_category', 'category', checkin.category)
    copy('checkin_score', 'score', checkin.score)
  } else {
    copy('note_text', 'text', (payload as NotePayload).text)
  }
  return changed
}

function buildPayload(type: EntryType, form: EntryFormState, errors: FieldErrors): EntryPayload {
  if (type === 'meal') {
    const description = form.description
    if (!description) errors['payload.description'] = 'Описание обязательно.'
    if (description.length > 2000) errors['payload.description'] = 'Максимум 2000 символов.'

    return compact({
      description,
      mass_g: parseNullableNumber(form.mass_g, 'payload.mass_g', errors),
      nutrients: compact({
        energy_kcal: parseNullableNumber(form.energy_kcal, 'payload.nutrients.energy_kcal', errors),
        protein_g: parseNullableNumber(form.protein_g, 'payload.nutrients.protein_g', errors),
        fat_g: parseNullableNumber(form.fat_g, 'payload.nutrients.fat_g', errors),
        carbs_g: parseNullableNumber(form.carbs_g, 'payload.nutrients.carbs_g', errors),
      }),
      nutrients_basis: form.nutrients_basis || undefined,
    }) as MealPayload
  }

  if (type === 'metrics') {
    if (!form.metric_code) errors['payload.code'] = 'Показатель обязателен.'
    if (form.metric_unit.length > 32) errors['payload.unit'] = 'Максимум 32 символа.'
    if (form.metric_local_time && !/^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$/.test(form.metric_local_time)) {
      errors['payload.local_time'] = 'Время должно быть в формате HH:mm.'
    }

    const value = parseRequiredNumber(form.metric_value, 'payload.value', errors)
    if ((form.metric_code === 'steps' || form.metric_code === 'sleep_duration_min') && value !== null && value < 0) {
      errors['payload.value'] = 'Значение не может быть отрицательным по контракту.'
    }

    return compact({
      code: form.metric_code || undefined,
      value: value ?? 0,
      unit: form.metric_unit.trim() || null,
      local_date: form.metric_local_date || null,
      local_time: form.metric_local_time || null,
      qualifier: form.metric_qualifier || null,
    }) as MetricsPayload
  }

  if (type === 'checkin') {
    if (!form.checkin_category) errors['payload.category'] = 'Категория обязательна.'
    const score = parseRequiredInteger(form.checkin_score, 'payload.score', errors)
    if (score !== null && (score < 1 || score > 5)) {
      errors['payload.score'] = 'Оценка должна быть целым числом от 1 до 5.'
    }

    return compact({
      category: form.checkin_category || undefined,
      score: score ?? 1,
    }) as CheckinPayload
  }

  const text = form.note_text
  if (!text) errors['payload.text'] = 'Текст обязателен.'
  if (text.length > 2000) errors['payload.text'] = 'Максимум 2000 символов.'
  return { text } satisfies NotePayload
}

function parseNullableNumber(value: string, field: string, errors: FieldErrors) {
  if (value.trim() === '') return null
  const parsed = Number(value.replace(',', '.'))
  if (!Number.isFinite(parsed)) {
    errors[field] = 'Введите число или оставьте поле пустым.'
    return null
  }
  if (parsed < 0) {
    errors[field] = 'Значение не может быть отрицательным по контракту.'
  }
  return parsed
}

function parseRequiredNumber(value: string, field: string, errors: FieldErrors) {
  if (value.trim() === '') {
    errors[field] = 'Значение обязательно.'
    return null
  }

  const parsed = Number(value.replace(',', '.'))
  if (!Number.isFinite(parsed)) {
    errors[field] = 'Введите число.'
    return null
  }
  return parsed
}

function parseRequiredInteger(value: string, field: string, errors: FieldErrors) {
  const parsed = parseRequiredNumber(value, field, errors)
  if (parsed === null) return null
  if (!Number.isInteger(parsed)) {
    errors[field] = 'Введите целое число.'
    return null
  }
  return parsed
}

function compact<T extends Record<string, unknown>>(value: T) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as T
}

function toFieldErrors(errors: ApiError['field_errors']) {
  const result: FieldErrors = {}
  for (const error of errors ?? []) {
    const field = error.field.startsWith('/') ? error.field.slice(1).replaceAll('/', '.') : error.field
    result[field] = error.message
  }
  return result
}

function errorFor(errors: FieldErrors, field: string) {
  const withoutPayload = field.replace(/^payload\./, '')
  return errors[field] ?? errors[withoutPayload] ?? errors[`payload.${field}`]
}

function originHint(entry: Entry, field: string) {
  const origin = entry.field_origins[field] ?? entry.field_origins[`payload.${field}`]
  return origin ? `Происхождение: ${originLabels[origin]}.` : 'Происхождение не указано.'
}

function toInputValue(value: number | string | null | undefined) {
  return value === null || value === undefined ? '' : String(value)
}

function toZonedLocalInput(value: string | null | undefined, timezone: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''

  const parts = getZonedParts(date, timezone)
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}

function localInputToUtc(value: string, timezone: string) {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/)
  if (!match) return null

  const [, year, month, day, hour, minute, second = '00'] = match
  const localAsUtc = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second))
  if (!Number.isFinite(localAsUtc)) return null
  const wanted = `${year}-${month}-${day}T${hour}:${minute}:${second}`
  const offsets = new Set<number>()
  for (const hours of [-36, -12, 0, 12, 36]) {
    offsets.add(getTimeZoneOffset(new Date(localAsUtc + hours * 3_600_000), timezone))
  }
  const matches = [...offsets]
    .map((offset) => new Date(localAsUtc - offset))
    .filter((candidate) => {
      const parts = getZonedParts(candidate, timezone)
      return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}` === wanted
    })
    .sort((a, b) => a.getTime() - b.getTime())
  return matches.length === 1 ? matches[0].toISOString() : null
}

function getTimeZoneOffset(date: Date, timezone: string) {
  const parts = getZonedParts(date, timezone)
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  )
  return asUtc - date.getTime()
}

function getZonedParts(date: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(date)

  return Object.fromEntries(parts.map((part) => [part.type, part.value])) as Record<'year' | 'month' | 'day' | 'hour' | 'minute' | 'second', string>
}

function formatNullableDate(value: string | null | undefined, timezone: string) {
  if (!value) return 'неизвестно'
  return formatDate(value, timezone)
}

function formatDate(value: string, timezone: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return 'неизвестно'
  return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short', timeZone: timezone }).format(date)
}

function isSameInstant(left: string, right: string | null | undefined) {
  if (!right) return false
  const leftTime = new Date(left).getTime()
  const rightTime = new Date(right).getTime()
  return Number.isFinite(leftTime) && leftTime === rightTime
}

function newIdPart() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return Math.random().toString(36).slice(2)
}

function handleError(cause: unknown, expire: () => void, setMessage: (value: string) => void) {
  if (cause instanceof ApiError && cause.status === 401) {
    expire()
    return
  }

  setMessage(cause instanceof Error ? cause.message : 'Произошла ошибка.')
}

function metricDateLabel(entry: Entry) {
  const code = (entry.payload as MetricsPayload).code
  return code === 'steps' ? 'День итога шагов' : code === 'sleep_duration_min' ? 'Дата пробуждения' : 'Дата измерения'
}
