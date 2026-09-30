import type { AnalyticsResponse } from '../../overview/analytics.types'

import './StepsCard.css'

type Steps = AnalyticsResponse['cards']['steps']
type Period = AnalyticsResponse['period']

interface StepsCardProps {
  steps: Steps | null
  period: Period | null
}

function formatSteps(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('ru-RU')
}

function formatCount(
  value: number | null | undefined,
): string {
  if (value == null) {
    return '—'
  }

  return value.toLocaleString('ru-RU')
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
      className="steps-card"
      aria-label="Аналитика шагов"
    >
      <div className="steps-card__header">
        <h2 className="steps-card__title">
          Шаги
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
        Всего за период
      </p>

      <p className="steps-card__total">
        {formatSteps(steps?.total)}

        <span> шагов</span>
      </p>

      <div className="steps-card__details">
        <div className="steps-card__stat">
          <span className="steps-card__label">
            Среднее
          </span>

          <strong>
            {formatSteps(steps?.average)}
          </strong>

          <span className="steps-card__unit">
            шагов в день
          </span>
        </div>

        <div className="steps-card__stat">
          <span className="steps-card__label">
            Дней с данными
          </span>

          <strong>
            {formatCount(
              steps?.days_with_data,
            )}
          </strong>

          {daysInPeriod !== null && (
            <span className="steps-card__unit">
              из {daysInPeriod}
            </span>
          )}
        </div>
      </div>

      {coverage !== null && (
        <div className="steps-card__coverage">
          <div className="steps-card__coverage-header">
            <span>Данные за период</span>

            <strong>
              {coverage}%
            </strong>
          </div>

          <div
            className="steps-card__progress"
            role="progressbar"
            aria-label="Покрытие периода данными о шагах"
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
            Данные записаны за{' '}
            {steps?.days_with_data ?? 0}{' '}
            из {daysInPeriod} дней
          </p>
        </div>
      )}

      <p className="steps-card__period">
        {period
          ? `${formatDate(period.from)} — ${formatDate(period.to)}`
          : '—'}
      </p>
    </section>
  )
}