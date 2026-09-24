
import { apiClient } from '../api/client'

import type {
  ApiClient,
  ApiQueryParams,
} from '../api/client'

import type { AnalyticsResponse } from './analytics.types'

export function getAnalytics(
  query: ApiQueryParams,
  client: ApiClient = apiClient,
): Promise<AnalyticsResponse> {
  return client.get<AnalyticsResponse>(
    '/api/v1/analytics',
    {
      query,
    },
  )
}