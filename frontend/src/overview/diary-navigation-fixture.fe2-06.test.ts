
/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'
import { describe, expect, it } from 'vitest'

import type {
  CheckinPayload,
  Entry,
  MealPayload,
  MetricsPayload,
} from '../api/types'

import { readContractFixture } from './fixtures/fe2-06-contracts'
import { createDiaryNavigationEntries } from './fixtures/diary-navigation.fixture'

interface InputRecord {
  id: string
  type: 'meal' | 'metrics' | 'checkin'
  status: string
  local_date?: string
  wake_date?: string
  occurred_at?: string
  updated_at?: string
  mass_g?: number | null
  nutrients?: MealPayload['nutrients']
  nutrients_basis?: MealPayload['nutrients_basis']
  nutrients_per_100g?: MealPayload['nutrients']
  code?: MetricsPayload['code']
  value?: number
  qualifier?: MetricsPayload['qualifier']
  category?: CheckinPayload['category']
  score?: number
}

interface NormalInput {
  timezone: string
  records: InputRecord[]
}

function readNormalInput(): NormalInput {
  const path = resolve(
    cwd(),
    '..',
    'contracts',
    'fixtures',
    'analytics_input_normal.json',
  )

  return JSON.parse(
    readFileSync(path, 'utf8'),
  ) as NormalInput
}

function findEntry(entries: Entry[], id: string): Entry {
  const found = entries.find((item) => item.id === id)

  if (!found) {
    throw new Error(`Отсутствует исходная запись ${id}`)
  }

  return found
}

// Только проверка календарной даты навигационного fixture.
// Серверные агрегаты и численные формулы здесь не вычисляются.
function localDate(occurredAt: string, timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(occurredAt))

  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value

  return `${part('year')}-${part('month')}-${part('day')}`
}

describe('FE2-06: согласованность навигационного fixture', () => {
  it('содержит те же уникальные ID и статусы, что вход BE3', () => {
    const input = readNormalInput()
    const entries = createDiaryNavigationEntries()

    expect(entries).toHaveLength(input.records.length)

    expect(new Set(entries.map((item) => item.id)).size)
      .toBe(entries.length)

    expect(entries.map((item) => item.id).sort()).toEqual(
      input.records.map((item) => item.id).sort(),
    )

    for (const record of input.records) {
      const actual = findEntry(entries, record.id)

      expect(actual.type).toBe(record.type)
      expect(actual.status).toBe(record.status)
    }
  })

  it('сохраняет исходные значения без пересчёта нутриентов и метрик', () => {
    const input = readNormalInput()
    const entries = createDiaryNavigationEntries()

    for (const record of input.records) {
      const actual = findEntry(entries, record.id)

      if (record.type === 'meal') {
        if (!('description' in actual.payload)) {
          throw new Error(`Неверный payload еды ${record.id}`)
        }

        expect(actual.payload.mass_g).toBe(record.mass_g)

        if (record.nutrients_basis === 'per_100g') {
          expect(actual.payload.nutrients_basis).toBe('per_100g')
          expect(actual.payload.nutrients)
            .toEqual(record.nutrients_per_100g)
        } else {
          expect(actual.payload.nutrients).toEqual(record.nutrients)
        }
      }

      if (record.type === 'metrics') {
        if (!('code' in actual.payload)) {
          throw new Error(`Неверный payload метрики ${record.id}`)
        }

        expect(actual.payload.code).toBe(record.code)
        expect(actual.payload.value).toBe(record.value)

        if (record.qualifier !== undefined) {
          expect(actual.payload.qualifier).toBe(record.qualifier)
        }
      }

      if (record.type === 'checkin') {
        if (!('category' in actual.payload)) {
          throw new Error(`Неверный payload оценки ${record.id}`)
        }

        expect(actual.payload.category).toBe(record.category)
        expect(actual.payload.score).toBe(record.score)
      }
    }
  })

  it('сохраняет исходные даты, времена и единицы подтверждённых метрик', () => {
    const input = readNormalInput()
    const entries = createDiaryNavigationEntries()

    const units = {
      steps: 'count',
      sleep_duration_min: 'min',
      heart_rate: 'bpm',
    } as const

    for (const record of input.records) {
      const actual = findEntry(entries, record.id)

      const expectedDate =
        record.local_date ??
        record.wake_date ??
        (
          record.occurred_at
            ? localDate(record.occurred_at, input.timezone)
            : undefined
        )

      expect(expectedDate).toBeDefined()

      expect(
        localDate(actual.occurred_at, input.timezone),
      ).toBe(expectedDate)

      // Для исходных времён сравниваем момент, а не запись offset/Z.
      if (record.occurred_at) {
        expect(Date.parse(actual.occurred_at))
          .toBe(Date.parse(record.occurred_at))
      }

      if (record.updated_at) {
        expect(Date.parse(actual.updated_at))
          .toBe(Date.parse(record.updated_at))
      }

      if (record.type === 'metrics') {
        if (!('code' in actual.payload)) {
          throw new Error(`Неверный payload метрики ${record.id}`)
        }

        expect(actual.payload.local_date).toBe(expectedDate)
        expect(actual.payload.unit).toBe(units[actual.payload.code])
      }
    }
  })

  it('каждый источник эталонного ответа существует в дневнике на нужную дату', () => {
    const expected = readContractFixture('normal')
    const entries = createDiaryNavigationEntries()

    for (const source of expected.sources) {
      const actual = findEntry(entries, source.entry_id)

      expect(actual.type).toBe(source.type)
      expect(actual.status).toBe('confirmed')

      expect(
        localDate(actual.occurred_at, expected.period.timezone),
      ).toBe(source.local_date)
    }
  })

  it('точки сна, шагов и состояния ссылаются на правильный показатель', () => {
    const expected = readContractFixture('normal')
    const entries = createDiaryNavigationEntries()

    for (const [series, code, unit] of [
      [expected.series.sleep, 'sleep_duration_min', 'min'],
      [expected.series.steps, 'steps', 'count'],
    ] as const) {
      for (const point of series) {
        if (!point.source) {
          throw new Error('В normal fixture ожидается источник точки')
        }

        const actual = findEntry(entries, point.source.entry_id)

        if (!('code' in actual.payload)) {
          throw new Error(`Неверный payload ${actual.id}`)
        }

        expect(actual.type).toBe('metrics')
        expect(actual.payload.code).toBe(code)
        expect(actual.payload.unit).toBe(unit)
        expect(actual.payload.value).toBe(point.value)
        expect(actual.payload.local_date).toBe(point.date)
      }
    }

    for (const point of expected.series.checkin.points) {
      if (!point.source) {
        throw new Error('В normal fixture ожидается источник оценки')
      }

      const actual = findEntry(entries, point.source.entry_id)

      if (!('category' in actual.payload)) {
        throw new Error(`Неверный payload оценки ${actual.id}`)
      }

      expect(actual.type).toBe('checkin')
      expect(actual.payload.category)
        .toBe(expected.series.checkin.category)
      expect(actual.payload.score).toBe(point.value)
    }

    for (const point of expected.series.nutrition) {
      for (const source of point.source) {
        const actual = findEntry(entries, source.entry_id)

        expect(actual.type).toBe('meal')
        expect(
          localDate(actual.occurred_at, expected.period.timezone),
        ).toBe(point.date)
      }
    }
  })
})
