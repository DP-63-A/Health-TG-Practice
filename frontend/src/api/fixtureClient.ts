import { ApiError } from './errors'
import type { ApiBodyRequestOptions, ApiClient, ApiRequestOptions, Entry, EntryFilters, EntryPatchRequest, ConfirmRequest, TelegramAuthResponse, User } from './types'

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
    field_origins: { description: 'estimated', mass_g: 'estimated' }, submission_id: null,
  },
  {
    id: '22222222-2222-4222-8222-222222222203', user_id: user.id, type: 'metrics', status: 'confirmed', source_kind: 'watch_photo',
    source_ref: { file_id: null, telegram_update_id: 9003, telegram_message_id: 44, label: 'Снимок часов' },
    occurred_at: '2026-09-17T20:00:00Z', created_at: '2026-09-17T20:01:00Z', updated_at: '2026-09-17T20:01:00Z', revision: 1,
    payload: { code: 'steps', value: 8200, unit: 'count', local_date: '2026-09-17', local_time: null, qualifier: null },
    field_origins: { value: 'extracted' }, submission_id: 'sub_metrics_01',
  },
]

export const fixtureApiClient: ApiClient = {
  async get<TResponse>(path: string, options?: ApiRequestOptions) {
    const pathname = normalizePath(path)
    if (pathname === '/me') return clone(user) as TResponse
    if (pathname === '/entries') return clone(listEntries(options?.query as EntryFilters | undefined)) as TResponse
    const id = matchEntry(pathname)
    if (id) return clone(findEntry(id)) as TResponse
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
      checkRevision(entry, body.expected_revision)
      entry.status = 'confirmed'; entry.submission_id = body.submission_id
    } else entry.status = 'cancelled'
    bump(entry)
    return clone(entry) as TResponse
  },
  async patch<TResponse, TBody>(path: string, options?: ApiBodyRequestOptions<TBody>) {
    const id = matchEntry(normalizePath(path)); if (!id) throw routeError('PATCH', path, 404)
    const entry = findEntry(id); const body = options?.body as EntryPatchRequest
    checkRevision(entry, body.expected_revision)
    if (body.occurred_at) entry.occurred_at = body.occurred_at
    if (body.payload) entry.payload = { ...entry.payload, ...body.payload } as Entry['payload']
    if (body.field_origins) entry.field_origins = { ...entry.field_origins, ...body.field_origins }
    bump(entry); return clone(entry) as TResponse
  },
  async delete<TResponse>(path: string, options?: ApiRequestOptions) {
    const id = matchEntry(normalizePath(path)); if (!id) throw routeError('DELETE', path, 404)
    const entry = findEntry(id)
    const revision = Number(String(new Headers(options?.headers).get('If-Match')).replaceAll('"', ''))
    checkRevision(entry, revision); entry.status = 'deleted'; bump(entry); return clone(entry) as TResponse
  },
}

function listEntries(filters: EntryFilters = {}) {
  const status = filters.status ?? 'confirmed'
  const items = entries.filter((entry) => entry.status === status && (!filters.type || entry.type === filters.type) &&
    (!filters.from || entry.occurred_at.slice(0, 10) >= filters.from) && (!filters.to || entry.occurred_at.slice(0, 10) <= filters.to))
  return { items: items.slice(0, filters.limit ?? 20), next_cursor: null }
}
function findEntry(id: string) { const entry = entries.find((item) => item.id === id); if (!entry) throw routeError('GET', `/entries/${id}`, 404); return entry }
function matchEntry(path: string) { return path.match(/^\/entries\/([^/]+)$/)?.[1] }
function checkRevision(entry: Entry, revision: number) { if (entry.revision !== revision) throw new ApiError({ code: 'VERSION_CONFLICT', message: `expected_revision ${revision} is stale; current revision is ${entry.revision}`, request_id: 'fixture-conflict' }, 409) }
function bump(entry: Entry) { entry.revision += 1; entry.updated_at = new Date().toISOString() }
function clone<T>(value: T): T { return structuredClone(value) }
function normalizePath(path: string) { return new URL(path, 'http://fixture.local').pathname }
function routeError(method: string, path: string, status: number) { return new ApiError({ code: 'RESOURCE_NOT_FOUND', message: `Fixture for ${method} ${normalizePath(path)} is not defined`, request_id: 'fixture' }, status) }
