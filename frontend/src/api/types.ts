export type ApiMode = 'live' | 'fixture'

export type EntryType = 'meal' | 'metrics' | 'checkin' | 'note'
export type EntryStatus = 'draft' | 'confirmed' | 'cancelled' | 'deleted'
export type SourceKind =
  | 'text'
  | 'food_photo'
  | 'health_screenshot'
  | 'watch_photo'
  | 'quick_checkin'
  | 'seed'
export type FieldOrigin = 'reported' | 'extracted' | 'estimated' | 'computed'

export interface SourceRef {
  file_id?: string | null
  telegram_update_id?: number | null
  telegram_message_id?: number | null
  label?: string | null
}

export interface MealPayload {
  description: string
  mass_g?: number | null
  nutrients?: {
    energy_kcal?: number | null
    protein_g?: number | null
    fat_g?: number | null
    carbs_g?: number | null
  }
  nutrients_basis?: 'per_100g' | 'per_serving' | 'unknown'
}
export interface MetricsPayload {
  code: 'steps' | 'sleep_duration_min' | 'heart_rate'
  value: number
  unit: string
  local_date: string
  local_time?: string | null
  qualifier?: 'instant' | 'resting' | null
}
export interface CheckinPayload {
  category: 'sleep_quality' | 'digestion_comfort' | 'wellbeing' | 'mood'
  score: number
}
export interface NotePayload { text: string }
export type EntryPayload = MealPayload | MetricsPayload | CheckinPayload | NotePayload

export interface Entry {
  id: string
  user_id: string
  type: EntryType
  status: EntryStatus
  source_kind: SourceKind
  source_ref: SourceRef
  occurred_at: string
  created_at: string
  updated_at: string
  revision: number
  payload: EntryPayload
  field_origins: Record<string, FieldOrigin>
  submission_id: string | null
}

export interface EntryListResponse { items: Entry[]; next_cursor?: string | null }
export interface EntryFilters {
  from?: string
  to?: string
  type?: EntryType
  status?: EntryStatus
  limit?: number
  cursor?: string
}
export interface EntryPatchRequest {
  expected_revision: number
  occurred_at?: string
  payload?: Partial<EntryPayload>
  field_origins?: Record<string, FieldOrigin>
}
export interface ConfirmRequest { submission_id: string; expected_revision: number }
export interface User { id: string; telegram_id: number; timezone: string; stand_access: boolean }
export interface TelegramAuthResponse {
  session_token: string
  token_type: 'Bearer'
  expires_in: 3600
  user: User
}

export type ApiQueryPrimitive = string | number | boolean
export type ApiQueryValue =
  | ApiQueryPrimitive
  | null
  | undefined
  | readonly ApiQueryPrimitive[]

export type ApiQueryParams = Record<string, ApiQueryValue>

export interface ApiRequestOptions {
  query?: ApiQueryParams
  headers?: HeadersInit
  signal?: AbortSignal
}

export interface ApiBodyRequestOptions<TBody = unknown>
  extends ApiRequestOptions {
  body?: TBody
}

export interface ApiClient {
  get<TResponse>(
    path: string,
    options?: ApiRequestOptions,
  ): Promise<TResponse>
  post<TResponse, TBody = unknown>(
    path: string,
    options?: ApiBodyRequestOptions<TBody>,
  ): Promise<TResponse>
  patch<TResponse, TBody = unknown>(
    path: string,
    options?: ApiBodyRequestOptions<TBody>,
  ): Promise<TResponse>
  delete<TResponse = void>(
    path: string,
    options?: ApiRequestOptions,
  ): Promise<TResponse>
}
