import { createLiveApiClient } from './liveClient'
import type { ApiClient, ApiMode } from './types'

export { ApiError } from './errors'
export type {
  ApiClient,
  ApiMode,
  ApiQueryParams,
  ApiRequestOptions,
  ApiBodyRequestOptions,
  Entry,
  EntryFilters,
  EntryListResponse,
  EntryPatchRequest,
  EntryPayload,
  EntryStatus,
  EntryType,
  ConfirmRequest,
  TelegramAuthResponse,
  User,
} from './types'

export const apiMode = readApiMode()
export const apiClient: ApiClient =
  import.meta.env.VITE_API_MODE === 'fixture'
    ? (await import('./fixtureClient')).fixtureApiClient
    : createLiveApiClient(readApiBaseUrl())

function readApiMode(): ApiMode {
  const value = import.meta.env.VITE_API_MODE ?? 'live'

  if (value === 'live' || value === 'fixture') {
    return value
  }

  throw new Error(
    `Unsupported VITE_API_MODE "${value}". Expected "live" or "fixture".`,
  )
}

function readApiBaseUrl() {
  const value = import.meta.env.VITE_API_BASE_URL?.trim()

  if (!value) {
    throw new Error('VITE_API_BASE_URL is required when VITE_API_MODE=live.')
  }

  return value
}
