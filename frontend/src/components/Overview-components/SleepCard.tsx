
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './SleepCard.css'

type Sleep = AnalyticsResponse['cards']['sleep']
type Period = AnalyticsResponse['period']

interface SleepCardProps {
  sleep: Sleep | null
  period: Period | null
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
    return `${remainingMinutes} мин`
  }

  if (remainingMinutes === 0) {
    return `${hours} ч`
  }

  return `${hours} ч ${remainingMinutes} мин`
}

function formatCount(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('ru-RU')
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
}: SleepCardProps) {
  return (
    <section
      className="sleep-card"
      aria-label="Аналитика сна"
    >
      <h2 className="sleep-card__title">
        Сон
      </h2>

      <p className="sleep-card__label">
        Всего за период
      </p>

      <p className="sleep-card__total">
        {formatSleep(sleep?.total_minutes)}
      </p>

      <div className="sleep-card__details">
        <div>
          <span className="sleep-card__label">
            Среднее за день с данными
          </span>

          <strong>
            {formatSleep(
              sleep?.average_minutes,
            )}
          </strong>
        </div>

        <div>
          <span className="sleep-card__label">
            Дней с данными
          </span>

          <strong>
            {formatCount(
              sleep?.days_with_data,
            )}
          </strong>
        </div>
      </div>

      <p className="sleep-card__period">
        Период:{' '}

        {period
          ? `${formatDate(period.from)} — ${formatDate(period.to)}`
          : '—'}
      </p>
    </section>
  )
}