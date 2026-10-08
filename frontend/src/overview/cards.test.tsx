import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

import type { AnalyticsResponse } from './analytics.types'

import { NutritionCard } from '../components/Overview-components/NutritionCard'
import { MealCountCard } from '../components/Overview-components/MealCountCard'
import { SleepCard } from '../components/Overview-components/SleepCard'
import { StepsCard } from '../components/Overview-components/StepsCard'
import { HeartRateCard } from '../components/Overview-components/HeartRateCard'
import { CheckinCard } from '../components/Overview-components/CheckinCard'

function renderText(component: ReactElement): string {
  return renderToStaticMarkup(component)
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/[\u00a0\u202f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

const examplePeriod: AnalyticsResponse['period'] = {
  kind: 'days_7',
  from: '2026-09-13',
  to: '2026-09-19',
  timezone: 'Europe/Warsaw',
}

const exampleNutrition: AnalyticsResponse['cards']['nutrition'] = {
  energy_kcal: 930,
  protein_g: 50,
  fat_g: 30,
  carbs_g: 110,
  incomplete: false,
  meals_with_energy: 2,
}

const exampleMealCount: AnalyticsResponse['cards']['meal_count'] = {
  count: 2,
}

const exampleSleep: AnalyticsResponse['cards']['sleep'] = {
  total_minutes: 900,
  average_minutes: 450,
  days_with_data: 2,
}

const exampleSteps: AnalyticsResponse['cards']['steps'] = {
  total: 5000,
  average: 5000,
  days_with_data: 1,
}

const exampleHeartRate: AnalyticsResponse['cards']['heart_rate'] = {
  value_bpm: 72,
  occurred_at: '2026-09-19T10:15:00Z',
  local_date: '2026-09-19',
  local_time: null,
  qualifier: 'resting',
  entry_id: '22222222-2222-4222-8222-222222222221',
}

const exampleCheckins: AnalyticsResponse['cards']['checkins'] = {
  sleep_quality: {
    score: 4,
    date: '2026-09-19',
    entry_id: '22222222-2222-4222-8222-222222222222',
  },
  digestion_comfort: {
    score: 5,
    date: '2026-09-19',
    entry_id: '22222222-2222-4222-8222-222222222223',
  },
  wellbeing: {
    score: 3,
    date: '2026-09-19',
    entry_id: '22222222-2222-4222-8222-222222222224',
  },
  mood: {
    score: 4,
    date: '2026-09-19',
    entry_id: '22222222-2222-4222-8222-222222222225',
  },
}

describe('NutritionCard', () => {
  it('отображает калории и Macros из ответа BE3', () => {
    const text = renderText(
      <NutritionCard nutrition={exampleNutrition} />,
    )

    
  expect(text).toContain('930 kcal')
  expect(text).toContain('Protein 50 g')
  expect(text).toContain('Fat 30 g')
  expect(text).toContain('Carbs 110 g')
  })

  it('показывает прочерки при отсутствии данных', () => {
    const text = renderText(
      <NutritionCard nutrition={null} />,
    )

    expect(text).toContain('— kcal')
    expect(text).toContain('Protein — g')
    expect(text).toContain('Fat — g')
    expect(text).toContain('Carbs — g')
  })

  it('показывает ноль как число, а не как прочерк', () => {
    const text = renderText(
      <NutritionCard
        nutrition={{
          ...exampleNutrition,
          protein_g: 0,
        }}
      />,
    )

    expect(text).toContain('Protein 0 g')
  })

  it('показывает предупреждение о неполном рационе', () => {
    const text = renderText(
      <NutritionCard
        nutrition={{
          ...exampleNutrition,
          incomplete: true,
        }}
      />,
    )

    expect(text).toContain('Nutrition data is incomplete')
  })

  it('не показывает предупреждение при полном рационе', () => {
    const text = renderText(
      <NutritionCard nutrition={exampleNutrition} />,
    )

    expect(text).not.toContain('Nutrition data is incomplete')
  })
})

describe('MealCountCard', () => {
  it('отображает количество meals', () => {
    const text = renderText(
      <MealCountCard mealCount={exampleMealCount} />,
    )

    expect(text).toContain('For the selected period 2 meals')
  })

  it('показывает прочерк при отсутствии данных', () => {
    const text = renderText(
      <MealCountCard mealCount={null} />,
    )

    expect(text).toContain('For the selected period —')
  })

  it('показывает ноль при нулевом количестве', () => {
    const text = renderText(
      <MealCountCard mealCount={{ count: 0 }} />,
    )

    expect(text).toContain('For the selected period 0 meals')
  })
})

describe('SleepCard', () => {
  it('отображает общую и среднюю sleep duration', () => {
    const text = renderText(
      <SleepCard
        sleep={exampleSleep}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('7 h 30 min')
    expect(text).toContain('Days recorded 2')
    expect(text).toContain('13.09.2026')
    expect(text).toContain('19.09.2026')
  })

  it('показывает прочерки при отсутствии данных', () => {
    const text = renderText(
      <SleepCard
        sleep={null}
        period={null}
      />,
    )

    expect(text).toContain('Period total —')
    expect(text).toContain('Average — per recorded day')
    expect(text).toContain('Days recorded —')
    expect(text).toMatch(/—$/)
  })

  it('корректно отображает нулевую продолжительность', () => {
    const text = renderText(
      <SleepCard
        sleep={{
          total_minutes: 0,
          average_minutes: 0,
          days_with_data: 0,
        }}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('0 min')
    expect(text).toContain('Days recorded 0')
  })
})

describe('StepsCard', () => {
  it('отображает steps из ответа BE3', () => {
    const text = renderText(
      <StepsCard
        steps={exampleSteps}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('5,000')
    expect(text).toContain('Days recorded 1')
  })

  it('показывает прочерки при отсутствии данных', () => {
    const text = renderText(
      <StepsCard
        steps={null}
        period={null}
      />,
    )

    expect(text).toContain('Period total — steps')
    expect(text).toContain('Average — steps per day')
    expect(text).toContain('Days recorded —')
    expect(text).toMatch(/—$/)
  })

  it('не заменяет ноль прочерком', () => {
    const text = renderText(
      <StepsCard
        steps={{
          total: 0,
          average: 0,
          days_with_data: 0,
        }}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('Period total 0')
    expect(text).toContain('Days recorded 0')
  })
})

describe('HeartRateCard', () => {
  it('отделяет unknownе время измерения от известного времени сообщения', () => {
    const text = renderText(<HeartRateCard heartRate={{ value_bpm: 72,
      occurred_at: '2026-10-07T23:50:00Z', local_date: '2026-10-05', local_time: null, qualifier: null, entry_id: null }}
      period={examplePeriod} />)
    expect(text).toContain('2026-10-05 · time unknown')
    expect(text).toContain('Reported at')
    expect(text).toContain('Latest reported heart rate')
  })

  it('отображает последнее измерение пульса', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={exampleHeartRate}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('72')
    expect(text).toContain('bpm')
    expect(text).toContain('19/09/2026')
    expect(text).toContain('12:15')
    expect(text).toContain('2026-09-19 · time unknown')
    expect(text).toContain('Reported at')
    expect(text).toContain('At rest')
  })

  it('разделяет дату и время измерения и исходного сообщения', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={{
          ...exampleHeartRate,
          local_date: '2026-09-15',
          local_time: '08:30',
        }}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('Latest reported heart rate')
    expect(text).toContain('Measured at 2026-09-15 · 08:30')
    expect(text).toContain('Reported at 19/09/2026, 12:15')
    expect(text).not.toContain('Measured at 2026-09-19')
  })

  it('не подставляет время сообщения вместо неизвестного времени измерения', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={{
          ...exampleHeartRate,
          local_date: '2026-09-15',
          local_time: null,
        }}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('Measured at 2026-09-15 · time unknown')
    expect(text).toContain('Reported at 19/09/2026, 12:15')
    expect(text).not.toContain('2026-09-15 · 12:15')
  })

  it('показывает прочерки при отсутствии измерения', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={{
          value_bpm: null,
          occurred_at: null,
          local_date: null,
          local_time: null,
          qualifier: null,
          entry_id: null,
        }}
        period={examplePeriod}
      />,
    )

    expect(text).toContain('— bpm')
    expect(text).toContain('Measured at unknown')
    expect(text).not.toContain('At rest')
  })

  it('не придумывает контекст отсутствующего измерения', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={{
          ...exampleHeartRate,
          value_bpm: 62,
          qualifier: null,
        }}
        period={examplePeriod}
      />,
    )

  expect(text).toContain('62')
  expect(text).not.toContain('At rest')
  expect(text).not.toContain('Single measurement')
})

describe('CheckinCard', () => {
  it('отображает четыре оценки независимо друг от друга', () => {
    const text = renderText(
      <CheckinCard checkins={exampleCheckins} />,
    )

    expect(text).toContain('Sleep quality 4')
    expect(text).toContain('Digestive comfort 5')
    expect(text).toContain('Wellbeing 3')
    expect(text).toContain('Mood 4')
  })

  it('показывает прочерки при отсутствии всех оценок', () => {
    const text = renderText(
      <CheckinCard checkins={null} />,
    )

    expect(text).toContain('Sleep quality —')
    expect(text).toContain('Digestive comfort —')
    expect(text).toContain('Wellbeing —')
    expect(text).toContain('Mood —')
  })

  it('показывает прочерк для отсутствующей категории', () => {
    const text = renderText(
      <CheckinCard
        checkins={{
          ...exampleCheckins,
          mood: {
            score: null,
            date: null,
            entry_id: null,
          },
        }}
      />,
    )

    expect(text).toContain('Mood —')
  })
})
})
