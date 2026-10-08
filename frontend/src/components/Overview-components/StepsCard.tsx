import type { ReactNode } from 'react'
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './StepsCard.css'

type Steps = AnalyticsResponse['cards']['steps']
type Period = AnalyticsResponse['period']

interface StepsCardProps {
  steps: Steps | null
  period: Period | null
  children?: ReactNode
}

function formatSteps(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('en-GB')
}

function formatCount(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('en-GB')
}

function formatDate(date: string): string {
  const [year, month, day] = date.split('-')

  return `${day}.${month}.${year}`
}

function getDaysInPeriod(
  period: Period | null,
): number | null {
  if (!period) {
    return null
  }

  if (period.kind === 'today') {
    return 1
  }

  if (period.kind === 'days_7') {
    return 7
  }

  if (period.kind === 'days_21') {
    return 21
  }

  return null
}

export function StepsCard({
  steps,
  period,
  children,
}: StepsCardProps) {
  const daysInPeriod = getDaysInPeriod(period)

  const coverage =
    steps &&
    daysInPeriod &&
    daysInPeriod > 0
      ? Math.min(
          100,
          Math.round(
            (steps.days_with_data /
              daysInPeriod) *
              100,
          ),
        )
      : null

  return (
    <section
      className="steps-card paper-note"
      aria-label="Step analytics"
    >
      <div className="steps-card__header">
        <h2 className="steps-card__title">
          Steps
        </h2>

        <div
          className="steps-card__icon"
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
            <circle cx="14" cy="4" r="2" />
            <path d="M10 22l2-6 2 2 2 4" />
            <path d="M7 13l3-5 4 2 3 3" />
            <path d="M10 8l-2 5-3 2" />
          </svg>
        </div>
      </div>

      <p className="steps-card__label">
        Period total
      </p>

      <p className="steps-card__total">
        {formatSteps(steps?.total)}

        <span> steps</span>
      </p>

      <div className="steps-card__details">
        <div className="steps-card__stat">
          <span className="steps-card__label">
            Average
          </span>

          <strong>
            {formatSteps(steps?.average)}
          </strong>

          <span className="steps-card__unit">
            steps per day
          </span>
        </div>

        <div className="steps-card__stat">
          <span className="steps-card__label">
            Days recorded
          </span>

          <strong>
            {formatCount(
              steps?.days_with_data,
            )}
          </strong>

          {daysInPeriod !== null && (
            <span className="steps-card__unit">
              of {daysInPeriod}
            </span>
          )}
        </div>
      </div>

      {coverage !== null && (
        <div className="steps-card__coverage">
          <div className="steps-card__coverage-header">
            <span>Period coverage</span>

            <strong>
              {coverage}%
            </strong>
          </div>

          <div
            className="steps-card__progress"
            role="progressbar"
            aria-label="Step data coverage"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={coverage}
          >
            <div
              className="steps-card__progress-value"
              style={{
                width: `${coverage}%`,
              }}
            />
          </div>

          <p className="steps-card__coverage-description">
            Data recorded for{' '}
            {steps?.days_with_data ?? 0}{' '}
            of {daysInPeriod} days
          </p>
        </div>
      )}

      <p className="steps-card__period">
        {period
          ? `${formatDate(period.from)} — ${formatDate(period.to)}`
          : '—'}
      </p>
      {children}
    </section>
  )
}
