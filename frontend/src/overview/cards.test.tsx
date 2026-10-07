import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

import { analyticsFixture } from './fixtures/analytics.fixture'

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

describe('NutritionCard', () => {
  it('отображает калории и Macros из ответа BE3', () => {
    const text = renderText(
      <NutritionCard
        nutrition={analyticsFixture.cards.nutrition}
      />,
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
          ...analyticsFixture.cards.nutrition,
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
          ...analyticsFixture.cards.nutrition,
          incomplete: true,
        }}
      />,
    )

    expect(text).toContain('Nutrition data is incomplete')
  })

  it('не показывает предупреждение при полном рационе', () => {
    const text = renderText(
      <NutritionCard
        nutrition={analyticsFixture.cards.nutrition}
      />,
    )

    expect(text).not.toContain('Nutrition data is incomplete')
  })
})

describe('MealCountCard', () => {
  it('отображает количество meals', () => {
    const text = renderText(
      <MealCountCard
        mealCount={analyticsFixture.cards.meal_count}
      />,
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
        sleep={analyticsFixture.cards.sleep}
        period={analyticsFixture.period}
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
        period={analyticsFixture.period}
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
        steps={analyticsFixture.cards.steps}
        period={analyticsFixture.period}
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
        period={analyticsFixture.period}
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
      period={analyticsFixture.period} />)
    expect(text).toContain('2026-10-05 · time unknown')
    expect(text).toContain('Reported at')
    expect(text).toContain('Latest reported heart rate')
  })

  it('отображает последнее измерение пульса', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={analyticsFixture.cards.heart_rate}
        period={analyticsFixture.period}
      />,
    )

    expect(text).toContain('72')
    expect(text).toContain('bpm')
    expect(text).toContain('19/09/2026')
    expect(text).toContain('12:15')
    expect(text).toContain('2026-09-19 · 12:10')
    expect(text).toContain('Reported at')
    expect(text).toContain('At rest')
  })

  it('показывает прочерки при отсутствии пульса', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={null}
        period={null}
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
        value_bpm: 62,
        occurred_at: '2026-09-16T06:05:00Z',
        local_date: '2026-09-16',
        local_time: null,
        qualifier: null,
        entry_id: null,
      }}
      period={analyticsFixture.period}
    />,
  )

  expect(text).toContain('62')
  expect(text).not.toContain('At rest')
  expect(text).not.toContain('Single measurement')
})

describe('CheckinCard', () => {
  it('отображает четыре оценки независимо друг от друга', () => {
    const text = renderText(
      <CheckinCard
        checkins={analyticsFixture.cards.checkins}
      />,
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

  it('отображает ноль как число', () => {
    const text = renderText(
      <CheckinCard
        checkins={{
          ...analyticsFixture.cards.checkins,
          mood: {
            ...analyticsFixture.cards.checkins.mood,
            score: 0,
          },
        }}
      />,
    )

    expect(text).toContain('Mood 0')
  })
})
})
