
import type { Entry } from '../../api/types'

// Основа:
// contracts/fixtures/analytics_input_normal.json
//
// ID, значения, категории и календарные даты взяты из BE3.
//
// Синтетические поля для навигационного сценария:
// описания, source_ref, revision, submission_id, field_origins,
// created_at и недостающие времена occurred_at/updated_at.
//
// Время записей без исходного времени выбрано явно.
// Это не восстановленное фактическое время пользователя.
// Формат и отображение этих полей необходимо проверить с FE1.

const fixtureUserId =
  '11111111-1111-4111-8111-111111111101'

function entry(
  id: string,
  type: Entry['type'],
  payload: Entry['payload'],
  occurredAt: string,
  updatedAt = occurredAt,
): Entry {
  return {
    id,
    user_id: fixtureUserId,
    type,
    status: 'confirmed',
    source_kind: 'seed',
    source_ref: {
      label: 'FE2-06: согласованный навигационный fixture',
    },
    occurred_at: occurredAt,
    created_at: occurredAt,
    updated_at: updatedAt,
    revision: 1,
    payload,
    field_origins: {},
    submission_id: null,
  }
}

export function createDiaryNavigationEntries(): Entry[] {
  return [
    entry(
      '22222222-2222-4222-8222-222222222210',
      'meal',
      {
        description: 'Питание: готовые нутриенты порции',
        mass_g: null,
        nutrients: {
          energy_kcal: 600,
          protein_g: 30,
          fat_g: 20,
          carbs_g: 70,
        },
        // Навигационная адаптация готовых значений порции.
        // Подтвердить обозначение basis с FE1/BE3.
        nutrients_basis: 'per_serving',
      },
      '2026-09-19T09:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222211',
      'meal',
      {
        description: 'Питание: порция 200 г',
        mass_g: 200,
        nutrients_basis: 'per_100g',
        // Исходные значения на 100 г.
        // Значения для всей порции здесь не вычисляются.
        nutrients: {
          energy_kcal: 165,
          protein_g: 10,
          fat_g: 5,
          carbs_g: 20,
        },
      },
      '2026-09-19T10:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222214',
      'metrics',
      {
        code: 'steps',
        value: 3000,
        unit: 'count',
        local_date: '2026-09-19',
        local_time: '12:00',
        qualifier: null,
      },
      // Исходное время: 2026-09-19T12:00:00+02:00.
      '2026-09-19T10:00:00Z',
      '2026-09-19T10:01:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222215',
      'metrics',
      {
        code: 'steps',
        value: 5000,
        unit: 'count',
        local_date: '2026-09-19',
        local_time: '20:00',
        qualifier: null,
      },
      // Исходное время: 2026-09-19T20:00:00+02:00.
      '2026-09-19T18:00:00Z',
      '2026-09-19T18:01:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222216',
      'metrics',
      {
        code: 'sleep_duration_min',
        value: 420,
        unit: 'min',
        // Основа — wake_date исходного fixture.
        local_date: '2026-09-14',
        local_time: null,
        qualifier: null,
      },
      '2026-09-14T06:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222217',
      'metrics',
      {
        code: 'sleep_duration_min',
        value: 480,
        unit: 'min',
        local_date: '2026-09-16',
        local_time: null,
        qualifier: null,
      },
      '2026-09-16T06:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222221',
      'metrics',
      {
        code: 'heart_rate',
        value: 72,
        unit: 'bpm',
        local_date: '2026-09-19',
        local_time: '12:15',
        qualifier: 'resting',
      },
      '2026-09-19T10:15:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222222',
      'checkin',
      {
        category: 'sleep_quality',
        score: 4,
      },
      '2026-09-19T09:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222223',
      'checkin',
      {
        category: 'digestion_comfort',
        score: 5,
      },
      '2026-09-19T09:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222224',
      'checkin',
      {
        category: 'wellbeing',
        score: 3,
      },
      '2026-09-19T09:00:00Z',
    ),

    entry(
      '22222222-2222-4222-8222-222222222225',
      'checkin',
      {
        category: 'mood',
        score: 4,
      },
      '2026-09-19T09:00:00Z',
    ),
  ]
}
