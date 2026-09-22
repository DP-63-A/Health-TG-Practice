import { ApiError } from './errors'
import type {
  ApiBodyRequestOptions,
  ApiClient,
  ApiRequestOptions,
} from './types'

const fixtureMe = {
  id: 'fixture-user',
  display_name: 'Fixture User',
  mode: 'fixture',
}

export const fixtureApiClient: ApiClient = {
  async get<TResponse>(path: string, _options?: ApiRequestOptions) {
    if (normalizePath(path) === '/me') {
      return structuredClone(fixtureMe) as TResponse
    }

    throw fixtureRouteError('GET', path, 404)
  },
  async post<TResponse, TBody = unknown>(
    path: string,
    _options?: ApiBodyRequestOptions<TBody>,
  ) {
    throw fixtureRouteError('POST', path, 501) as TResponse
  },
  async patch<TResponse, TBody = unknown>(
    path: string,
    _options?: ApiBodyRequestOptions<TBody>,
  ) {
    throw fixtureRouteError('PATCH', path, 501) as TResponse
  },
  async delete<TResponse = void>(path: string, _options?: ApiRequestOptions) {
    throw fixtureRouteError('DELETE', path, 501) as TResponse
  },
}

function normalizePath(path: string) {
  return new URL(path, 'http://fixture.local').pathname
}

function fixtureRouteError(method: string, path: string, status: number) {
  return new ApiError(
    {
      code: 'fixture_route_not_found',
      message: `Fixture for ${method} ${normalizePath(path)} is not defined`,
      request_id: 'fixture',
    },
    status,
  )
}
