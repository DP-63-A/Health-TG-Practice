import { apiClient, apiMode } from './client'
import { getSessionToken } from '../auth/session'
import type { ApiClient, ApiQueryParams, ConfirmRequest, Entry, EntryFilters, EntryListResponse, EntryPatchRequest } from './types'

export function createEntriesApi(client: ApiClient = apiClient) {
  return {
    list: (filters: EntryFilters, signal?: AbortSignal) =>
      client.get<EntryListResponse>('/entries', { query: { ...filters } as ApiQueryParams, signal }),
    get: (id: string, signal?: AbortSignal) =>
      client.get<Entry>(`/entries/${encodeURIComponent(id)}`, { signal }),
    patch: (id: string, body: EntryPatchRequest) =>
      client.patch<Entry, EntryPatchRequest>(`/entries/${encodeURIComponent(id)}`, { body }),
    confirm: (id: string, body: ConfirmRequest) =>
      client.post<Entry, ConfirmRequest>(`/entries/${encodeURIComponent(id)}/confirm`, { body }),
    cancel: (id: string, revision: number) =>
      client.post<Entry>(`/entries/${encodeURIComponent(id)}/cancel`, {
        headers: { 'If-Match': `"${revision}"` },
      }),
    delete: (id: string, revision: number) =>
      client.delete<Entry>(`/entries/${encodeURIComponent(id)}`, {
        headers: { 'If-Match': `"${revision}"` },
      }),
    fileUrl: (fileId: string) => `/api/v1/files/${encodeURIComponent(fileId)}`,
    downloadFile: (fileId: string, signal?: AbortSignal) => downloadFile(fileId, signal),
  }
}

export const entriesApi = createEntriesApi()

async function downloadFile(fileId: string, signal?: AbortSignal) {
  if (apiMode === 'fixture') {
    return `blob:fixture/${encodeURIComponent(fileId)}`
  }

  const baseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
  if (!baseUrl) throw new Error('VITE_API_BASE_URL is required when VITE_API_MODE=live.')

  const headers = new Headers()
  const token = getSessionToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(buildUrl(baseUrl, `/files/${encodeURIComponent(fileId)}`), {
    headers,
    signal,
  })

  if (!response.ok) {
    throw new Error('Не удалось загрузить исходный файл.')
  }

  return URL.createObjectURL(await response.blob())
}

function buildUrl(baseUrl: string, path: string) {
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  const normalizedPath = path.replace(/^\/+/, '')
  const resolvedBaseUrl = new URL(normalizedBaseUrl, window.location.origin)
  return new URL(normalizedPath, resolvedBaseUrl).toString()
}
