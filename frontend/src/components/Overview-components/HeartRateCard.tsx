
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './HeartRateCard.css'

type HeartRate = AnalyticsResponse['cards']['heart_rate']
type Period = AnalyticsResponse['period']

interface HeartRateCardProps {
  heartRate: HeartRate | null
  period: Period | null
}

function formatPulse(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('ru-RU')
}

function formatMeasurementTime(
  value: string | null | undefined,
  timeZone: string | undefined,
): string {
  if (!value) {
    return '—'
  }

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return '—'
  }

  return new Intl.DateTimeFormat('ru-RU', {
    dateStyle: 'short',
    timeStyle: 'short',
    ...(timeZone ? { timeZone } : {}),
  }).format(date)
}

function formatQualifier(
  qualifier: string | null | undefined,
): string | null {
  if (qualifier === 'resting') {
    return 'В покое'
  }

  if (qualifier === 'instant') {
    return 'Разовое измерение'
  }

  return null
}

export function HeartRateCard({
  heartRate,
  period,
}: HeartRateCardProps) {
  const qualifierLabel = formatQualifier(
    heartRate?.qualifier,
  )

  return (
    <section
      className="heart-rate-card"
      aria-label="Аналитика пульса"
    >
      <h2 className="heart-rate-card__title">
        Пульс
      </h2>

      <p className="heart-rate-card__label">
        Последнее измерение
      </p>

      <p className="heart-rate-card__value">
        {formatPulse(heartRate?.value_bpm)}

        <span> уд/мин</span>
      </p>

      <div className="heart-rate-card__details">
        <p className="heart-rate-card__label">
          Время измерения
        </p>

        <p className="heart-rate-card__time">
          {formatMeasurementTime(
            heartRate?.occurred_at,
            period?.timezone,
          )}
        </p>

        {qualifierLabel && (
          <span className="heart-rate-card__qualifier">
            {qualifierLabel}
          </span>
        )}
      </div>
    </section>
  )
}