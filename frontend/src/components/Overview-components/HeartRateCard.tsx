import type { AnalyticsResponse } from '../../overview/analytics.types'

import './HeartRateCard.css'

type HeartRate =
  AnalyticsResponse['cards']['heart_rate']

type Period =
  AnalyticsResponse['period']

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
      <div className="heart-rate-card__header">
        <h2 className="heart-rate-card__title">
          Пульс
        </h2>

        <div
          className="heart-rate-card__icon"
          aria-hidden="true"
        >
          <svg
            viewBox="0 0 24 24"
            width="23"
            height="23"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8Z" />
          </svg>
        </div>
      </div>

      <p className="heart-rate-card__label">
        Последний сообщённый пульс
      </p>

      <div className="heart-rate-card__measurement">
        <p className="heart-rate-card__value">
          {formatPulse(heartRate?.value_bpm)}

          <span>уд/мин</span>
        </p>

        <div
          className="heart-rate-card__pulse"
          aria-hidden="true"
        >
          <svg
            viewBox="0 0 120 36"
            preserveAspectRatio="none"
          >
            <path
              d="
                M2 20
                H25
                L31 20
                L36 7
                L44 30
                L51 15
                L57 20
                H75
                L81 20
                L86 10
                L92 26
                L98 20
                H118
              "
            />
          </svg>
        </div>
      </div>

      <div className="heart-rate-card__details">
        <div className="heart-rate-card__time-info">
          <div
            className="heart-rate-card__clock"
            aria-hidden="true"
          >
            <svg
              viewBox="0 0 24 24"
              width="17"
              height="17"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <circle
                cx="12"
                cy="12"
                r="9"
              />

              <path d="M12 7v5l3 2" />
            </svg>
          </div>
          <div>
            <p className="heart-rate-card__label">Время измерения</p>
            <p className="heart-rate-card__time">
              {heartRate?.local_date ?? 'неизвестно'} · {heartRate?.local_time ?? 'время неизвестно'}
            </p>
            <p className="heart-rate-card__label">Сообщено</p>
            <p className="heart-rate-card__time">
              {formatMeasurementTime(heartRate?.occurred_at, period?.timezone)}
            </p>
          </div>
        </div>

        {qualifierLabel && (
          <span className="heart-rate-card__qualifier">
            {qualifierLabel}
          </span>
        )}
      </div>
    </section>
  )
}
