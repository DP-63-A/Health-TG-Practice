export type AnalyticsPeriod = 'today' | 'days_7' |
  'days_21'

  export type CheckinCategory =
    | 'sleep_quality'
    | 'digestion_comfort'
    | 'wellbeing'
    | 'mood'

  export interface AnalyticsSource {
    entry_id: string
    type: 'meal' | 'metrics' | 'checkin'
    local_date: string
  }

  export interface NutritionPoint {
    date: string
    energy_kcal: number | null
    source: AnalyticsSource[]
  }

  export interface SleepPoint {
    date: string
    value: number | null
    unit: 'min'
    source: AnalyticsSource | null
  }

  export interface StepsPoint {
    date: string
    value: number | null
    unit: 'count'
    source: AnalyticsSource | null
  }

  export interface CheckinPoint {
    date: string
    value: number | null
    unit: 'score_1_5'
    source: AnalyticsSource | null
  }

  export interface CheckinRating {
    score: number | null
    date: string | null
    entry_id: string | null
  }

  export interface AnalyticsResponse {
    period: {
      kind: AnalyticsPeriod
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
        entry_id: string | null
      }

      checkins: Record<CheckinCategory,
      CheckinRating>
    }

    series: {
      nutrition: NutritionPoint[]
      sleep: SleepPoint[]
      steps: StepsPoint[]

      checkin: {
        category: CheckinCategory
        points: CheckinPoint[]
      }
    }

    observations: {
      days_in_period: number
      days_with_any_data: number
      generated_at: string
    }

    sources: AnalyticsSource[]
  }
