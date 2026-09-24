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
  it('отображает калории и БЖУ из ответа BE3', () => {
    const text = renderText(
      <NutritionCard
        nutrition={analyticsFixture.cards.nutrition}
      />,
    )

    expect(text).toContain('930 ккал')
    expect(text).toContain('40 г')
    expect(text).toContain('20 г')
    expect(text).toContain('80 г')
  })

  it('показывает прочерки при отсутствии данных', () => {
    const text = renderText(
      <NutritionCard nutrition={null} />,
    )

    expect(text).toContain('— ккал')
    expect(text).toContain('Белки — г')
    expect(text).toContain('Жиры — г')
    expect(text).toContain('Углеводы — г')
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

    expect(text).toContain('Белки 0 г')
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

    expect(text).toContain('Данные о рационе неполные')
  })

  it('не показывает предупреждение при полном рационе', () => {
    const text = renderText(
      <NutritionCard
        nutrition={analyticsFixture.cards.nutrition}
      />,
    )

    expect(text).not.toContain('Данные о рационе неполные')
  })
})

describe('MealCountCard', () => {
  it('отображает количество приёмов пищи', () => {
    const text = renderText(
      <MealCountCard
        mealCount={analyticsFixture.cards.meal_count}
      />,
    )

    expect(text).toContain('Количество за выбранный период 2')
  })

  it('показывает прочерк при отсутствии данных', () => {
    const text = renderText(
      <MealCountCard mealCount={null} />,
    )

    expect(text).toContain('Количество за выбранный период —')
  })

  it('показывает ноль при нулевом количестве', () => {
    const text = renderText(
      <MealCountCard mealCount={{ count: 0 }} />,
    )

    expect(text).toContain('Количество за выбранный период 0')
  })
})

describe('SleepCard', () => {
  it('отображает общую и среднюю продолжительность сна', () => {
    const text = renderText(
      <SleepCard
        sleep={analyticsFixture.cards.sleep}
        period={analyticsFixture.period}
      />,
    )

    expect(text).toContain('7 ч 30 мин')
    expect(text).toContain('Дней с данными 1')
    expect(text).toContain('10.09.2026')
    expect(text).toContain('16.09.2026')
  })

  it('показывает прочерки при отсутствии данных', () => {
    const text = renderText(
      <SleepCard
        sleep={null}
        period={null}
      />,
    )

    expect(text).toContain('Всего за период —')
    expect(text).toContain('Среднее за день с данными —')
    expect(text).toContain('Дней с данными —')
    expect(text).toContain('Период: —')
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

    expect(text).toContain('0 мин')
    expect(text).toContain('Дней с данными 0')
  })
})

describe('StepsCard', () => {
  it('отображает шаги из ответа BE3', () => {
    const text = renderText(
      <StepsCard
        steps={analyticsFixture.cards.steps}
        period={analyticsFixture.period}
      />,
    )

    expect(text).toContain('8 432')
    expect(text).toContain('Дней с данными 1')
  })

  it('показывает прочерки при отсутствии данных', () => {
    const text = renderText(
      <StepsCard
        steps={null}
        period={null}
      />,
    )

    expect(text).toContain('Всего за период —')
    expect(text).toContain('Дней с данными —')
    expect(text).toContain('Период: —')
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

    expect(text).toContain('Всего за период 0')
    expect(text).toContain('Дней с данными 0')
  })
})

describe('HeartRateCard', () => {
  it('отображает последнее измерение пульса', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={analyticsFixture.cards.heart_rate}
        period={analyticsFixture.period}
      />,
    )

    expect(text).toContain('62')
    expect(text).toContain('уд/мин')
    expect(text).toContain('16.09.2026')
    expect(text).toContain('08:05')
    expect(text).toContain('В покое')
  })

  it('показывает прочерки при отсутствии пульса', () => {
    const text = renderText(
      <HeartRateCard
        heartRate={null}
        period={null}
      />,
    )

    expect(text).toContain('— уд/мин')
    expect(text).toContain('Время измерения —')
    expect(text).not.toContain('В покое')
  })

 
it('не придумывает контекст отсутствующего измерения', () => {
  const text = renderText(
    <HeartRateCard
      heartRate={{
        value_bpm: 62,
        occurred_at: '2026-09-16T06:05:00Z',
        qualifier: null,
      }}
      period={analyticsFixture.period}
    />,
  )

  expect(text).toContain('62')
  expect(text).not.toContain('В покое')
  expect(text).not.toContain('Разовое измерение')
})

describe('CheckinCard', () => {
  it('отображает четыре оценки независимо друг от друга', () => {
    const text = renderText(
      <CheckinCard
        checkins={analyticsFixture.cards.checkins}
      />,
    )

    expect(text).toContain('Качество сна 4')
    expect(text).toContain('Комфорт пищеварения —')
    expect(text).toContain('Самочувствие 3')
    expect(text).toContain('Настроение 4')
  })

  it('показывает прочерки при отсутствии всех оценок', () => {
    const text = renderText(
      <CheckinCard checkins={null} />,
    )

    expect(text).toContain('Качество сна —')
    expect(text).toContain('Комфорт пищеварения —')
    expect(text).toContain('Самочувствие —')
    expect(text).toContain('Настроение —')
  })

  it('отображает ноль как число', () => {
    const text = renderText(
      <CheckinCard
        checkins={{
          ...analyticsFixture.cards.checkins,
          mood: 0,
        }}
      />,
    )

    expect(text).toContain('Настроение 0')
  })
})
})