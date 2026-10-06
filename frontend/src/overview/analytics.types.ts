
export type CheckinCategory =
  | 'sleep_quality'
  | 'digestion_comfort'
  | 'wellbeing'
  | 'mood'

export interface AnalyticsPoint {
  date: string
  value: number | null
}

export interface AnalyticsResponse {
  period: {
    kind: string
    from: string
    to: string
    timezone: string
  }

  cards: {
    nutrition: {
      energy_kcal: number | null
      protein_g: number | null
      fat_g: number | null
      carbs_g: number | null
      incomplete: boolean
      meals_with_energy: number
    }

    meal_count: {
      count: number
    }

    sleep: {
      total_minutes: number | null
      average_minutes: number | null
      days_with_data: number
    }

    steps: {
      total: number | null
      average: number | null
      days_with_data: number
    }

    heart_rate: {
      value_bpm: number | null
      occurred_at: string | null
      local_date: string | null
      local_time: string | null
      qualifier: 'instant' | 'resting' | null
    } | null

    checkins: Record<CheckinCategory, number | null>
  }

  series: {
    nutrition: AnalyticsPoint[]
    sleep: AnalyticsPoint[]
    steps: AnalyticsPoint[]

    checkin: {
      category: CheckinCategory
      points: AnalyticsPoint[]
    }
  }

  observations: {
    days_in_period: number
    days_with_any_data: number
    generated_at: string
  }

  sources: {
    entry_id: string
    type: 'meal' | 'metrics' | 'checkin'
    local_date: string
  }[]
}
