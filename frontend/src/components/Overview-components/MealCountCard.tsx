
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './MealCountCard.css'

type MealCount = AnalyticsResponse['cards']['meal_count']

interface MealCountCardProps {
  mealCount: MealCount | null
}

function formatCount(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('ru-RU')
}

export function MealCountCard({
  mealCount,
}: MealCountCardProps) {
  return (
    <section
      className="meal-count-card"
      aria-label="Количество приёмов пищи"
    >
      <h2 className="meal-count-card__title">
        Приёмы пищи
      </h2>

      <p className="meal-count-card__label">
        Количество за выбранный период
      </p>

      <p className="meal-count-card__total">
        {formatCount(mealCount?.count)}
      </p>
    </section>
  )
}