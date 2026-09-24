import { getSessionToken } from '../auth/session'
import { ApiError, type ApiErrorPayload, type ApiFieldError } from './errors'
import type {
  ApiBodyRequestOptions,
  ApiClient,
  ApiQueryParams,
  ApiQueryPrimitive,
  ApiRequestOptions,
} from './types'

const REQUEST_ID_HEADER = 'x-request-id'

export function createLiveApiClient(baseUrl: string): ApiClient {
  return {
    get: <TResponse>(path: string, options?: ApiRequestOptions) =>
      request<TResponse>(baseUrl, 'GET', path, options),
    post: <TResponse, TBody = unknown>(
      path: string,
      options?: ApiBodyRequestOptions<TBody>,
    ) => request<TResponse, TBody>(baseUrl, 'POST', path, options),
    patch: <TResponse, TBody = unknown>(
      path: string,
      options?: ApiBodyRequestOptions<TBody>,
    ) => request<TResponse, TBody>(baseUrl, 'PATCH', path, options),
    delete: <TResponse = void>(path: string, options?: ApiRequestOptions) =>
      request<TResponse>(baseUrl, 'DELETE', path, options),
  }
}

async function request<TResponse, TBody = unknown>(
  baseUrl: string,
  method: string,
  path: string,
  options: ApiBodyRequestOptions<TBody> = {},
): Promise<TResponse> {
  const headers = new Headers(options.headers)
  const token = getSessionToken()

  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const init: RequestInit = {
    method,
    headers,
    signal: options.signal,
  }

  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    init.body = JSON.stringify(options.body)
  }

  const response = await fetch(buildUrl(baseUrl, path, options.query), init)

  if (!response.ok) {
    throw await readApiError(response)
  }

  if (response.status === 204) {
    return undefined as TResponse
  }

  const contentType = response.headers.get('content-type') ?? ''

  if (contentType.includes('application/json')) {
    return (await response.json()) as TResponse
  }

  return (await response.text()) as TResponse
}

function buildUrl(baseUrl: string, path: string, query?: ApiQueryParams) {
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  const normalizedPath = path.replace(/^\/+/, '')
  const url = new URL(normalizedPath, normalizedBaseUrl)

  if (!query) {
    return url
  }

  for (const [key, value] of Object.entries(query)) {
    appendQueryValue(url.searchParams, key, value)
  }

  return url
}

function appendQueryValue(
  searchParams: URLSearchParams,
  key: string,
  value: ApiQueryPrimitive | null | undefined | readonly ApiQueryPrimitive[],
) {
  if (value === null || value === undefined) {
    return
  }

  if (Array.isArray(value)) {
    for (const item of value) {
      searchParams.append(key, String(item))
    }
    return
  }

  searchParams.set(key, String(value))
}

async function readApiError(response: Response) {
  const fallbackRequestId = response.headers.get(REQUEST_ID_HEADER) ?? ''
  const fallbackPayload: ApiErrorPayload = {
    code: 'http_error',
    message: response.statusText || 'Request failed',
    request_id: fallbackRequestId,
  }

  const contentType = response.headers.get('content-type') ?? ''

  if (!contentType.includes('application/json')) {
    return new ApiError(fallbackPayload, response.status)
  }

  try {
    const body = (await response.json()) as unknown

    if (!isApiErrorBody(body)) {
      return new ApiError(fallbackPayload, response.status)
    }

    return new ApiError(
      {
        code: body.code,
        message: body.message,
        request_id: body.request_id || fallbackRequestId,
        field_errors: body.field_errors,
      },
      response.status,
    )
  } catch {
    return new ApiError(fallbackPayload, response.status)
  }
}

function isApiErrorBody(value: unknown): value is ApiErrorPayload {
  if (!isRecord(value)) {
    return false
  }

  return (
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    typeof value.request_id === 'string' &&
    isFieldErrors(value.field_errors)
  )
}

function isFieldErrors(value: unknown): value is ApiFieldError[] | undefined {
  if (value === undefined) {
    return true
  }

  if (!Array.isArray(value)) {
    return false
  }
  return value.every((item) => isRecord(item) && typeof item.field === 'string' &&
    typeof item.message === 'string' && (item.code === undefined || typeof item.code === 'string'))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
