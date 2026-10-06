import { analyticsFixture } from '../overview/fixtures/analytics.fixture'
import { ApiError } from './errors'
import type { ApiBodyRequestOptions, ApiClient, ApiRequestOptions, Entry, EntryFilters, EntryPatchRequest, ConfirmRequest, MetricsPayload, TelegramAuthResponse, User } from './types'
import type { AnalyticsResponse } from '../overview/analytics.types'

const user: User = { id: '11111111-1111-4111-8111-111111111101', telegram_id: 10001, timezone: 'Europe/Warsaw', stand_access: true }
const entries: Entry[] = [
  {
    id: '22222222-2222-4222-8222-222222222201', user_id: user.id, type: 'meal', status: 'confirmed', source_kind: 'food_photo',
    source_ref: { file_id: '33333333-3333-4333-8333-333333333301', telegram_update_id: 9001, telegram_message_id: 42, label: 'Фото еды' },
    occurred_at: '2026-09-16T11:30:00Z', created_at: '2026-09-16T11:31:00Z', updated_at: '2026-09-16T11:35:00Z', revision: 3,
    payload: { description: 'Овсянка с ягодами', mass_g: 200, nutrients: { energy_kcal: 330, protein_g: 20, fat_g: 10, carbs_g: 40 }, nutrients_basis: 'per_100g' },
    field_origins: { description: 'estimated', mass_g: 'reported' }, submission_id: 'sub_meal_01',
  },
  {
    id: '22222222-2222-4222-8222-222222222202', user_id: user.id, type: 'meal', status: 'draft', source_kind: 'food_photo',
    source_ref: { file_id: '33333333-3333-4333-8333-333333333302', telegram_update_id: 9002, telegram_message_id: 43, label: 'Фото ожидает проверки' },
    occurred_at: '2026-09-16T12:00:00Z', created_at: '2026-09-16T12:01:00Z', updated_at: '2026-09-16T12:01:00Z', revision: 1,
    payload: { description: 'Паста', mass_g: null, nutrients: { energy_kcal: 450, protein_g: null, fat_g: null, carbs_g: null }, nutrients_basis: 'per_serving' },
    field_origins: { description: 'estimated', mass_g: 'estimated', 'nutrients.energy_kcal': 'estimated', nutrients_basis: 'estimated' }, submission_id: null,
  },
  {
    id: '22222222-2222-4222-8222-222222222206', user_id: user.id, type: 'metrics', status: 'draft', source_kind: 'watch_photo',
    source_ref: { file_id: null, telegram_update_id: 9006, telegram_message_id: 47, label: 'Метрика ожидает проверки' },
    occurred_at: '2026-09-20T07:10:00Z', created_at: '2026-09-20T07:11:00Z', updated_at: '2026-09-20T07:11:00Z', revision: 1,
    payload: { code: 'sleep_duration_min', value: 430, unit: null, local_date: null, local_time: null, qualifier: null },
    field_origins: { code: 'extracted', value: 'extracted', unit: 'estimated', local_date: 'estimated' }, submission_id: null,
  },
  {
    id: '22222222-2222-4222-8222-222222222207', user_id: user.id, type: 'checkin', status: 'draft', source_kind: 'quick_checkin',
    source_ref: { file_id: null, telegram_update_id: 9007, telegram_message_id: 48, label: 'Оценка ожидает проверки' },
    occurred_at: '2026-09-20T08:30:00Z', created_at: '2026-09-20T08:31:00Z', updated_at: '2026-09-20T08:31:00Z', revision: 1,
    payload: { category: 'wellbeing', score: 3 },
    field_origins: { category: 'reported', score: 'reported' }, submission_id: null,
  },
  {
    id: '22222222-2222-4222-8222-222222222208', user_id: user.id, type: 'note', status: 'draft', source_kind: 'text',
    source_ref: { file_id: null, telegram_update_id: 9008, telegram_message_id: 49, label: 'Заметка ожидает проверки' },
    occurred_at: '2026-09-20T10:00:00Z', created_at: '2026-09-20T10:01:00Z', updated_at: '2026-09-20T10:01:00Z', revision: 1,
    payload: { text: 'Нужно проверить заметку' },
    field_origins: { text: 'reported' }, submission_id: null,
  },
  {
    id: '22222222-2222-4222-8222-222222222203', user_id: user.id, type: 'metrics', status: 'confirmed', source_kind: 'watch_photo',
    source_ref: { file_id: null, telegram_update_id: 9003, telegram_message_id: 44, label: 'Снимок часов' },
    occurred_at: '2026-09-17T20:00:00Z', created_at: '2026-09-17T20:01:00Z', updated_at: '2026-09-17T20:01:00Z', revision: 1,
    payload: { code: 'steps', value: 8200, unit: 'count', local_date: '2026-09-17', local_time: null, qualifier: null },
    field_origins: { value: 'extracted' }, submission_id: 'sub_metrics_01',
  },
  {
    id: '22222222-2222-4222-8222-222222222204', user_id: user.id, type: 'checkin', status: 'confirmed', source_kind: 'quick_checkin',
    source_ref: { file_id: null, telegram_update_id: 9004, telegram_message_id: 45, label: 'Быстрая отметка' },
    occurred_at: '2026-09-18T08:15:00Z', created_at: '2026-09-18T08:16:00Z', updated_at: '2026-09-18T08:16:00Z', revision: 1,
    payload: { category: 'mood', score: 4 },
    field_origins: { category: 'reported', score: 'reported' }, submission_id: 'sub_checkin_01',
  },
  {
    id: '22222222-2222-4222-8222-222222222205', user_id: user.id, type: 'note', status: 'confirmed', source_kind: 'text',
    source_ref: { file_id: null, telegram_update_id: 9005, telegram_message_id: 46, label: 'Текстовое сообщение' },
    occurred_at: '2026-09-19T09:10:00Z', created_at: '2026-09-19T09:11:00Z', updated_at: '2026-09-19T09:11:00Z', revision: 2,
    payload: { text: 'После завтрака чувствую себя хорошо' },
    field_origins: { text: 'reported' }, submission_id: 'sub_note_01',
  },
]

export const fixtureApiClient: ApiClient = {
async get<TResponse>(path: string, options?: ApiRequestOptions) {
  const pathname = normalizePath(path)

  if (pathname === '/me') {
    return clone(user) as TResponse
  }

  if (pathname === '/entries') {
    return clone(
      listEntries(options?.query as EntryFilters | undefined),
    ) as TResponse
  }

  // if (pathname === '/api/v1/analytics') {
  //   return clone(analyticsFixture) as TResponse
  // }

  if (pathname === '/api/v1/analytics') {
    // const response = clone(analyticsFixture)
    const response: AnalyticsResponse = clone(analyticsFixture)
    const requestedCategory =
    options?.query?.checkin_category

    const categories = [
      'sleep_quality',
      'digestion_comfort',
      'wellbeing',
      'mood',
    ] as const

    const category = categories.includes(
      requestedCategory as (typeof categories) [number],
    )
      ? (requestedCategory as (typeof categories) [number])
      : 'mood'

    const rating = response.cards.checkins[category]

    response.series.checkin = {
      category,
      points:
        rating.score !== null &&
        rating.date !== null &&
        rating.entry_id !== null
          ? [
              {
                date: rating.date,
                value: rating.score,
                unit: 'score_1_5',
                source: {
                  entry_id: rating.entry_id,
                  type: 'checkin',
                  local_date: rating.date,
                },
              },
            ]
          : [],
    }

    return response as TResponse
  }


  

  const id = matchEntry(pathname)

  if (id) {
    return clone(findEntry(id)) as TResponse
  }

  throw routeError('GET', path, 404)
},

  async post<TResponse, TBody>(path: string, options?: ApiBodyRequestOptions<TBody>) {
    const pathname = normalizePath(path)
    if (pathname === '/auth/telegram') return clone({ session_token: 'fixture-session', token_type: 'Bearer', expires_in: 3600, user } satisfies TelegramAuthResponse) as TResponse
    const match = pathname.match(/^\/entries\/([^/]+)\/(confirm|cancel)$/)
    if (!match) throw routeError('POST', path, 404)
    const entry = findEntry(match[1])
    if (match[2] === 'confirm') {
      const body = options?.body as ConfirmRequest
      if (entry.status === 'confirmed' && entry.submission_id === body.submission_id) return clone(entry) as TResponse
      checkDraft(entry)
      checkRevision(entry, body.expected_revision)
      if (entry.type === 'metrics') {
        const payload = entry.payload as MetricsPayload
        const field_errors = [
          ...(!payload.unit ? [{ field: 'payload.unit', message: 'unit is required for confirmed metrics' }] : []),
          ...(!payload.local_date ? [{ field: 'payload.local_date', message: 'local_date is required for confirmed metrics' }] : []),
        ]
        if (field_errors.length) throw new ApiError({ code: 'VALIDATION_ERROR', message: 'Request body failed validation', request_id: 'fixture-validation', field_errors }, 422)
      }
      entry.status = 'confirmed'; entry.submission_id = body.submission_id
    } else { checkDraft(entry); entry.status = 'cancelled' }
    bump(entry)
    return clone(entry) as TResponse
  },
  async patch<TResponse, TBody>(path: string, options?: ApiBodyRequestOptions<TBody>) {
    const id = matchEntry(normalizePath(path)); if (!id) throw routeError('PATCH', path, 404)
    const entry = findEntry(id); const body = options?.body as EntryPatchRequest
    if (entry.status !== 'draft' && entry.status !== 'confirmed') throw invalidStatus(entry)
    checkRevision(entry, body.expected_revision)
    const wasSteps = entry.type === 'metrics' && (entry.payload as MetricsPayload).code === 'steps'
    const nextCode = body.payload && 'code' in body.payload ? body.payload.code : (entry.payload as MetricsPayload).code
    if ((entry.type === 'metrics' && wasSteps !== (nextCode === 'steps'))
      || (wasSteps && body.occurred_at && Date.parse(body.occurred_at) !== Date.parse(entry.occurred_at))) {
      throw new ApiError({ code: 'VALIDATION_ERROR', message: 'Нельзя менять время исходного итога или преобразовывать шаги в другую метрику.', request_id: 'fixture-validation' }, 422)
    }
    validatePatch(entry, body)
    if (body.occurred_at) entry.occurred_at = body.occurred_at
    if (body.payload) {
      const current = entry.payload as unknown as Record<string, unknown>
      const incoming = body.payload as Record<string, unknown>
      entry.payload = {
        ...current,
        ...incoming,
        ...(entry.type === 'meal' && incoming.nutrients ? {
          nutrients: { ...(current.nutrients as Record<string, unknown> | undefined), ...(incoming.nutrients as Record<string, unknown>) },
        } : {}),
      } as Entry['payload']
    }
    if (body.field_origins) entry.field_origins = { ...entry.field_origins, ...body.field_origins }
    bump(entry); return clone(entry) as TResponse
  },
  async delete<TResponse>(path: string, options?: ApiRequestOptions) {
    const id = matchEntry(normalizePath(path)); if (!id) throw routeError('DELETE', path, 404)
    const entry = findEntry(id)
    const ifMatch = new Headers(options?.headers).get('If-Match')
    if (!ifMatch || !/^"[1-9][0-9]*"$/.test(ifMatch)) {
      throw new ApiError({ code: 'VALIDATION_ERROR', message: 'If-Match must be a quoted revision', request_id: 'fixture-validation' }, 422)
    }
    const revision = Number(ifMatch.slice(1, -1))
    if (entry.status !== 'confirmed') throw invalidStatus(entry)
    checkRevision(entry, revision); entry.status = 'deleted'; bump(entry); return clone(entry) as TResponse
  },
}

function listEntries(filters: EntryFilters = {}) {
  const status = filters.status ?? 'confirmed'
  const limit = filters.limit ?? 20
  const start = filters.cursor ? Number(filters.cursor) : 0
  const items = entries.filter((entry) => {
    const day = entry.type === 'metrics' && (entry.payload as MetricsPayload).code === 'steps'
      ? (entry.payload as MetricsPayload).local_date : entry.occurred_at.slice(0, 10)
    return entry.status === status && (!filters.type || entry.type === filters.type) &&
      (!filters.from || Boolean(day && day >= filters.from)) && (!filters.to || Boolean(day && day <= filters.to))
  })
  const page = items.slice(start, start + limit)
  const next = start + limit < items.length ? String(start + limit) : null
  return { items: page, next_cursor: next }
}
function findEntry(id: string) { const entry = entries.find((item) => item.id === id); if (!entry) throw routeError('GET', `/entries/${id}`, 404); return entry }
function matchEntry(path: string) { return path.match(/^\/entries\/([^/]+)$/)?.[1] }
function checkRevision(entry: Entry, revision: number) { if (entry.revision !== revision) throw new ApiError({ code: 'VERSION_CONFLICT', message: `expected_revision ${revision} is stale; current revision is ${entry.revision}`, request_id: 'fixture-conflict' }, 409) }
function checkDraft(entry: Entry) { if (entry.status !== 'draft') throw invalidStatus(entry) }
function invalidStatus(entry: Entry) { return new ApiError({ code: 'INVALID_STATUS_TRANSITION', message: `Cannot mutate entry ${entry.id} with status ${entry.status}`, request_id: 'fixture-invalid-status' }, 409) }
function validatePatch(entry: Entry, body: EntryPatchRequest) {
  const field_errors: { field: string; message: string; code?: string }[] = []
  const payload = body.payload as Record<string, unknown> | undefined
  if (!payload) return

  if (entry.type === 'meal') {
    if ('description' in payload && (typeof payload.description !== 'string' || payload.description.length < 1 || payload.description.length > 2000)) {
      field_errors.push({ field: 'payload.description', code: 'LENGTH', message: 'description length must be 1-2000' })
    }
    if ('nutrients_basis' in payload && !['per_100g', 'per_serving', 'unknown'].includes(String(payload.nutrients_basis))) {
      field_errors.push({ field: 'payload.nutrients_basis', code: 'ENUM', message: 'invalid nutrients_basis' })
    }
    for (const field of ['mass_g']) {
      if (typeof payload[field] === 'number' && payload[field] < 0) {
        field_errors.push({ field: `payload.${field}`, code: 'MINIMUM', message: `${field} must be >= 0` })
      }
    }
    const nutrients = payload.nutrients as Record<string, unknown> | undefined
    for (const field of ['energy_kcal', 'protein_g', 'fat_g', 'carbs_g']) {
      if (nutrients && typeof nutrients[field] === 'number' && nutrients[field] < 0) {
        field_errors.push({ field: `payload.nutrients.${field}`, code: 'MINIMUM', message: `${field} must be >= 0` })
      }
    }
  }

  if (entry.type === 'metrics') {
    if ('code' in payload && !['steps', 'sleep_duration_min', 'heart_rate'].includes(String(payload.code))) {
      field_errors.push({ field: 'payload.code', code: 'ENUM', message: 'invalid metric code' })
    }
    if (typeof payload.value !== 'number') {
      if ('value' in payload) field_errors.push({ field: 'payload.value', code: 'TYPE', message: 'value must be a number' })
    }
    const code = payload.code ?? (entry.payload as MetricsPayload).code
    const value = payload.value ?? (entry.payload as MetricsPayload).value
    if ((code === 'steps' || code === 'sleep_duration_min') && typeof value === 'number' && value < 0) {
      field_errors.push({ field: 'payload.value', code: 'MINIMUM', message: 'value must be >= 0' })
    }
    if ('unit' in payload && payload.unit !== null && (typeof payload.unit !== 'string' || payload.unit.length < 1 || payload.unit.length > 32)) {
      field_errors.push({ field: 'payload.unit', code: 'LENGTH', message: 'unit length must be 1-32' })
    }
    if ('local_date' in payload && payload.local_date !== null && (typeof payload.local_date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(payload.local_date))) {
      field_errors.push({ field: 'payload.local_date', code: 'FORMAT', message: 'invalid local_date' })
    }
    if (entry.status === 'confirmed') {
      const current = entry.payload as MetricsPayload
      if (('unit' in payload ? payload.unit : current.unit) == null) field_errors.push({ field: 'payload.unit', code: 'REQUIRED', message: 'unit is required' })
      if (('local_date' in payload ? payload.local_date : current.local_date) == null) field_errors.push({ field: 'payload.local_date', code: 'REQUIRED', message: 'local_date is required' })
    }
  }

  if (entry.type === 'checkin') {
    if ('category' in payload && !['sleep_quality', 'digestion_comfort', 'wellbeing', 'mood'].includes(String(payload.category))) {
      field_errors.push({ field: 'payload.category', code: 'ENUM', message: 'invalid category' })
    }
    if ('score' in payload && (typeof payload.score !== 'number' || !Number.isInteger(payload.score) || payload.score < 1 || payload.score > 5)) {
      field_errors.push({ field: 'payload.score', code: 'OUT_OF_RANGE', message: 'score must be an integer from 1 to 5' })
    }
  }

  if (entry.type === 'note' && 'text' in payload && (typeof payload.text !== 'string' || payload.text.length < 1 || payload.text.length > 2000)) {
    field_errors.push({ field: 'payload.text', code: 'LENGTH', message: 'text length must be 1-2000' })
  }

  if (field_errors.length > 0) {
    throw new ApiError({ code: 'VALIDATION_ERROR', message: 'Request body failed validation', request_id: 'fixture-validation', field_errors }, 422)
  }
}
function bump(entry: Entry) { entry.revision += 1; entry.updated_at = new Date().toISOString() }
function clone<T>(value: T): T { return structuredClone(value) }
function normalizePath(path: string) { return new URL(path, 'http://fixture.local').pathname }
function routeError(method: string, path: string, status: number) { return new ApiError({ code: 'RESOURCE_NOT_FOUND', message: `Fixture for ${method} ${normalizePath(path)} is not defined`, request_id: 'fixture' }, status) }
