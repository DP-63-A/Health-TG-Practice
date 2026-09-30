
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './CheckinCard.css'

type Checkins = AnalyticsResponse['cards']['checkins']

interface CheckinCardProps {
  checkins: Checkins | null
}

function formatScore(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('ru-RU')
}

export function CheckinCard({
  checkins,
}: CheckinCardProps) {
   const scores = [
      {
        key: 'sleep_quality',
        label: 'Качество сна',
        value: checkins?.sleep_quality.score,
      },
      {
        key: 'digestion_comfort',
        label: 'Комфорт пищеварения',
        value: checkins?.digestion_comfort.score,
      },
      {
        key: 'wellbeing',
        label: 'Самочувствие',
        value: checkins?.wellbeing.score,
      },
      {
        key: 'mood',
        label: 'Настроение',
        value: checkins?.mood.score,
      },
    ]

  return (
    <section
      className="checkin-card"
      aria-label="Субъективные оценки состояния"
    >
      <h2 className="checkin-card__title">
        Оценки
      </h2>

      <p className="checkin-card__description">
        Последние записанные оценки
      </p>

      <div className="checkin-card__scores">
        {scores.map((score) => (
          <div
            className="checkin-card__row"
            key={score.key}
          >
            <span className="checkin-card__label">
              {score.label}
            </span>

            <strong className="checkin-card__value">
              {formatScore(score.value)}
            </strong>
          </div>
        ))}
      </div>
    </section>
  )
}