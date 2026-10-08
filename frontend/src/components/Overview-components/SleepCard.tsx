import type { ReactNode } from 'react'
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './SleepCard.css'

type Sleep = AnalyticsResponse['cards']['sleep']
type Period = AnalyticsResponse['period']

interface SleepCardProps {
  sleep: Sleep | null
  period: Period | null
  children?: ReactNode
}

// Переводим минуты в часы и минуты.
// Агрегаты BE3 не пересчитываем.

function formatSleep(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  // Округляем только для отображения.
  const minutes = Math.round(value)

  const hours = Math.floor(minutes / 60)
  const remainingMinutes = minutes % 60

  if (hours === 0) {
    return `${remainingMinutes} min`
  }

  if (remainingMinutes === 0) {
    return `${hours} h`
  }

  return `${hours} h ${remainingMinutes} min`
}

function formatCount(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('en-GB')
}

// Используем готовую календарную дату BE3,
// не пересчитываем её через часовой пояс браузера.

function formatDate(date: string): string {
  const [year, month, day] = date.split('-')

  return `${day}.${month}.${year}`
}

export function SleepCard({
  sleep,
  period,
  children,
}: SleepCardProps) {
  return (
    <section
      className="sleep-card paper-note"
      aria-label="Sleep analytics"
    >
      <div className="sleep-card__header">
        <h2 className="sleep-card__title">
          Sleep
        </h2>

        <div
          className="sleep-card__icon"
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
            <path d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5a8.5 8.5 0 1 0 10.7 10.7Z" />
          </svg>
        </div>
      </div>

      <div className="sleep-card__main">
        <p className="sleep-card__label">
          Period total
        </p>

        <p className="sleep-card__total">
          {formatSleep(
            sleep?.total_minutes,
          )}
        </p>
      </div>

      <div className="sleep-card__details">
        <div className="sleep-card__stat">
          <span className="sleep-card__label">
            Average
          </span>

          <strong>
            {formatSleep(
              sleep?.average_minutes,
            )}
          </strong>

          <span className="sleep-card__hint">
            per recorded day
          </span>
        </div>

        <div className="sleep-card__stat">
          <span className="sleep-card__label">
            Days recorded
          </span>

          <strong>
            {formatCount(
              sleep?.days_with_data,
            )}
          </strong>

          <span className="sleep-card__hint">
            in the selected period
          </span>
        </div>
      </div>

      <div className="sleep-card__period">
        <span
          className="sleep-card__calendar"
          aria-hidden="true"
        >
          <svg
            viewBox="0 0 24 24"
            width="15"
            height="15"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <rect
              x="3"
              y="5"
              width="18"
              height="16"
              rx="2"
            />

            <path d="M16 3v4M8 3v4M3 10h18" />
          </svg>
        </span>

        <span>
          {period
            ? `${formatDate(period.from)} — ${formatDate(period.to)}`
            : '—'}
        </span>
      </div>
      {children}
    </section>
  )
}
