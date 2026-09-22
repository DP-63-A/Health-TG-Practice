export type ApiMode = 'live' | 'fixture'

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
