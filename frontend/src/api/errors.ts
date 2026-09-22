export type ApiFieldErrors = Record<string, string[]>

export interface ApiErrorPayload {
  code: string
  message: string
  request_id: string
  field_errors?: ApiFieldErrors
}

export class ApiError extends Error {
  readonly code: string
  readonly request_id: string
  readonly field_errors?: ApiFieldErrors
  readonly status: number

  constructor(payload: ApiErrorPayload, status: number) {
    super(payload.message)
    this.name = 'ApiError'
    this.code = payload.code
    this.request_id = payload.request_id
    this.field_errors = payload.field_errors
    this.status = status
  }
}
