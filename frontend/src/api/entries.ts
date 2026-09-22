import { apiClient } from './client'
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
    cancel: (id: string) => client.post<Entry>(`/entries/${encodeURIComponent(id)}/cancel`),
    delete: (id: string, revision: number) =>
      client.delete<Entry>(`/entries/${encodeURIComponent(id)}`, {
        headers: { 'If-Match': `"${revision}"` },
      }),
    fileUrl: (fileId: string) => `/api/v1/files/${encodeURIComponent(fileId)}`,
  }
}

export const entriesApi = createEntriesApi()
