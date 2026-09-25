import type { AnalyticsResponse } from '../analytics.types'

export const analyticsFixture: AnalyticsResponse = {
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