import type { AnalyticsResponse } from '../../overview/analytics.types'

import './CheckinCard.css'

type Checkins =
  AnalyticsResponse['cards']['checkins']

interface CheckinCardProps {
  checkins: Checkins | null
}

const MAX_SCORE = 5

function formatScore(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return `${value.toLocaleString('en-GB')} out of 5`
}

export function CheckinCard({
  checkins,
}: CheckinCardProps) {

  const scores = [
    {
      key: 'sleep_quality',
      label: 'Sleep quality',
      value: checkins?.sleep_quality.score,
    },
    {
      key: 'digestion_comfort',
      label: 'Digestive comfort',
      value: checkins?.digestion_comfort.score,
    },
    {
      key: 'wellbeing',
      label: 'Wellbeing',
      value: checkins?.wellbeing.score,
    },
    {
      key: 'mood',
      label: 'Mood',
      value: checkins?.mood.score,
    },
  ]

     
  return (
    <section
      className="checkin-card paper-note"
      aria-label="Subjective wellbeing scores"
    >
      <div className="checkin-card__header">
        <div>
          <h2 className="checkin-card__title">
            Scores
          </h2>

          <p className="checkin-card__description">
            Latest recorded scores
          </p>
        </div>

        <div
          className="checkin-card__icon"
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
            <circle
              cx="12"
              cy="12"
              r="9"
            />

            <path d="M8.5 10h.01" />
            <path d="M15.5 10h.01" />
            <path d="M8.5 14.5c1 1.2 2.1 1.8 3.5 1.8s2.5-.6 3.5-1.8" />
          </svg>
        </div>
      </div>

      <div className="checkin-card__scores">
        {scores.map((score) => {
  return (
    <div
      className="checkin-card__item"
      key={score.key}
    >
      <div className="checkin-card__row">
        <span className="checkin-card__label">
          {score.label}
        </span>

        <strong className="checkin-card__value">
          {formatScore(score.value)}
        </strong>
      </div>

      <div
        className="checkin-card__scale"
        aria-hidden="true"
      >
        {Array.from({
          length: MAX_SCORE,
        }).map((_, index) => {
          const isActive =
            score.value != null &&
            index < score.value

          return (
            <span
              key={index}
              className={[
                'checkin-card__segment',
                isActive
                  ? 'checkin-card__segment--active'
                  : '',
              ]
                .filter(Boolean)
                .join(' ')}
            />
          )
        })}
      </div>
    </div>
  )
})}
      </div>
    </section>
  )
}