import type { AnalyticsResponse } from '../../overview/analytics.types'

import './MealCountCard.css'

type MealCount =
  AnalyticsResponse['cards']['meal_count']

interface MealCountCardProps {
  mealCount: MealCount | null
}

const MAX_VISIBLE_MARKERS = 8

function formatCount(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('en-GB')
}

function getMealWord(count: number): string {
  return count === 1 ? 'meal' : 'meals'
}

export function MealCountCard({
  mealCount,
}: MealCountCardProps) {
  const count = mealCount?.count

  const visibleMarkers =
    count == null
      ? 0
      : Math.min(count, MAX_VISIBLE_MARKERS)

  const remaining =
    count == null
      ? 0
      : Math.max(
          count - MAX_VISIBLE_MARKERS,
          0,
        )

  return (
    <section
      className="meal-count-card paper-note"
      aria-label="Meal count"
    >
      <div className="meal-count-card__header">
        <h2 className="meal-count-card__title">
          Meals
        </h2>

        <div
          className="meal-count-card__icon"
          aria-hidden="true"
        >
          <svg
            viewBox="0 0 24 24"
            width="24"
            height="24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M7 3v8" />
            <path d="M4.5 3v4.5A3.5 3.5 0 0 0 8 11" />
            <path d="M9.5 3v4.5A3.5 3.5 0 0 1 6 11" />
            <path d="M7 11v10" />

            <path d="M16 3v18" />
            <path d="M16 3c2.5 1.5 3.5 4 3.5 6.5H16" />
          </svg>
        </div>
      </div>

      <p className="meal-count-card__label">
        For the selected period
      </p>

      <div className="meal-count-card__value">
        <strong>
          {formatCount(count)}
        </strong>

        {count != null && (
          <span>
            {getMealWord(count)}
          </span>
        )}
      </div>

      {count != null && (
        <div
          className="meal-count-card__visual"
          aria-hidden="true"
        >
          <div className="meal-count-card__markers">
            {Array.from({
              length: visibleMarkers,
            }).map((_, index) => (
              <span
                className="meal-count-card__marker"
                key={index}
              />
            ))}
          </div>

          {remaining > 0 && (
            <span className="meal-count-card__remaining">
              +{remaining}
            </span>
          )}
        </div>
      )}
    </section>
  )
}