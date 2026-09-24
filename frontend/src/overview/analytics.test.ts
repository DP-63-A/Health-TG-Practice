
import { describe, expect, it, vi } from 'vitest'

import { ApiError } from '../api/client'

import type {
  ApiClient,
  ApiRequestOptions,
} from '../api/client'

import { getAnalytics } from './analytics'

import type {
  AnalyticsResponse,
} from './analytics.types'

// Тестовый ответ на основе согласованного JSON BE3

const analyticsFixture: AnalyticsResponse = {
  period: {
    kind: 'days_7',
    from: '2026-09-10',
    to: '2026-09-16',
    timezone: 'Europe/Warsaw',
  },

  cards: {
    nutrition: {
      energy_kcal: 930,
      protein_g: 40,
      fat_g: 20,
      carbs_g: 80,
      incomplete: false,
      meals_with_energy: 2,
    },

    meal_count: {
      count: 2,
    },

    sleep: {
      total_minutes: 450,
      average_minutes: 450,
      days_with_data: 1,
    },

    steps: {
      total: 8432,
      average: 8432,
      days_with_data: 1,
    },

    heart_rate: {
      value_bpm: 62,
      occurred_at: '2026-09-16T06:05:00Z',
      qualifier: 'resting',
    },

    checkins: {
      sleep_quality: 4,
      digestion_comfort: null,
      wellbeing: 3,
      mood: 4,
    },
  },

  series: {
    nutrition: [
      { date: '2026-09-10', value: null },
      { date: '2026-09-11', value: 600 },
      { date: '2026-09-12', value: null },
      { date: '2026-09-13', value: null },
      { date: '2026-09-14', value: null },
      { date: '2026-09-15', value: null },
      { date: '2026-09-16', value: 330 },
    ],

    sleep: [
      { date: '2026-09-10', value: null },
      { date: '2026-09-11', value: null },
      { date: '2026-09-12', value: null },
      { date: '2026-09-13', value: null },
      { date: '2026-09-14', value: null },
      { date: '2026-09-15', value: null },
      { date: '2026-09-16', value: 450 },
    ],

    steps: [
      { date: '2026-09-10', value: null },
      { date: '2026-09-11', value: null },
      { date: '2026-09-12', value: null },
      { date: '2026-09-13', value: null },
      { date: '2026-09-14', value: null },
      { date: '2026-09-15', value: null },
      { date: '2026-09-16', value: 8432 },
    ],

    checkin: {
      category: 'mood',

      points: [
        { date: '2026-09-10', value: null },
        { date: '2026-09-11', value: null },
        { date: '2026-09-12', value: null },
        { date: '2026-09-13', value: null },
        { date: '2026-09-14', value: null },
        { date: '2026-09-15', value: null },
        { date: '2026-09-16', value: 4 },
      ],
    },
  },

  observations: {
    days_in_period: 7,
    days_with_any_data: 2,
    generated_at: '2026-09-16T12:00:00Z',
  },

  sources: [
    {
      entry_id: '22222222-2222-4222-8222-222222222206',
      type: 'meal',
      local_date: '2026-09-11',
    },
    {
      entry_id: '22222222-2222-4222-8222-222222222201',
      type: 'meal',
      local_date: '2026-09-16',
    },
    {
      entry_id: '22222222-2222-4222-8222-222222222203',
      type: 'metrics',
      local_date: '2026-09-16',
    },
    {
      entry_id: '22222222-2222-4222-8222-222222222207',
      type: 'metrics',
      local_date: '2026-09-16',
    },
    {
      entry_id: '22222222-2222-4222-8222-222222222208',
      type: 'metrics',
      local_date: '2026-09-16',
    },
    {
      entry_id: '22222222-2222-4222-8222-222222222204',
      type: 'checkin',
      local_date: '2026-09-16',
    },
  ],
}

// Создаём заменяемый API-клиент для тестов.
// Настоящий backend здесь не вызывается.

function createTestClient(
  get: ApiClient['get'],
): ApiClient {
  const unused = async (): Promise<never> => {
    throw new Error('Unexpected API method')
  }

  return {
    get,
    post: unused,
    patch: unused,
    delete: unused,
  }
}

describe('getAnalytics', () => {

  // ТЕСТ 1
  // Проверяем endpoint и параметры запроса

  it('передаёт endpoint и параметры в общий клиент FE1', async () => {
    const getSpy = vi.fn()

    const client = createTestClient(
      async <TResponse>(
        path: string,
        options?: ApiRequestOptions,
      ): Promise<TResponse> => {
        getSpy(path, options)

        return analyticsFixture as TResponse
      },
    )

    const query = {
      period: 'days_7',
    }

    await getAnalytics(query, client)

    expect(getSpy).toHaveBeenCalledTimes(1)

    expect(getSpy).toHaveBeenCalledWith(
      '/api/v1/analytics',
      {
        query: {
          period: 'days_7',
        },
      },
    )
  })

  // ТЕСТ 2
  // Проверяем успешное получение ответа BE3

  it('возвращает успешный ответ аналитики без изменений', async () => {
    const client = createTestClient(
      async <TResponse>(): Promise<TResponse> => {
        return analyticsFixture as TResponse
      },
    )

    const result = await getAnalytics(
      { period: 'days_7' },
      client,
    )

    expect(result).toEqual(analyticsFixture)

    expect(result.cards.nutrition.energy_kcal).toBe(930)

    expect(result.cards.sleep.total_minutes).toBe(450)

    expect(result.cards.steps.total).toBe(8432)

    expect(result.cards.checkins.mood).toBe(4)

    expect(
      result.cards.checkins.digestion_comfort,
    ).toBeNull()
  })

  // ТЕСТ 3
  // Проверяем передачу ошибки сервера

  it('передаёт ошибку общего клиента дальше', async () => {
    const apiError = new ApiError(
      {
        code: 'INTERNAL_ERROR',
        message: 'Analytics unavailable',
        request_id: 'test-request',
      },
      500,
    )

    const client = createTestClient(
      async <TResponse>(): Promise<TResponse> => {
        throw apiError
      },
    )

    await expect(
      getAnalytics(
        { period: 'days_7' },
        client,
      ),
    ).rejects.toBe(apiError)
  })

  // ТЕСТ 4
  // Проверяем, что ошибка 401 не скрывается

  it('не скрывает ошибку истёкшей сессии', async () => {
    const apiError = new ApiError(
      {
        code: 'UNAUTHORIZED',
        message: 'Session expired',
        request_id: 'test-auth',
      },
      401,
    )

    const client = createTestClient(
      async <TResponse>(): Promise<TResponse> => {
        throw apiError
      },
    )

    await expect(
      getAnalytics(
        { period: 'days_7' },
        client,
      ),
    ).rejects.toBe(apiError)

    expect(apiError.status).toBe(401)
  })

})