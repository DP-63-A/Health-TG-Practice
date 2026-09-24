
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

export function StepsCard({
  steps,
  period,
}: StepsCardProps) {
  return (
    <section
      className="steps-card"
      aria-label="Аналитика шагов"
    >
      <h2 className="steps-card__title">
        Шаги
      </h2>

      <p className="steps-card__label">
        Всего за период
      </p>

      <p className="steps-card__total">
        {formatSteps(steps?.total)}
        <span> шагов</span>
      </p>

      <div className="steps-card__details">
        <div>
          <span className="steps-card__label">
            Среднее
          </span>

          <strong>
            {formatSteps(steps?.average)}
          </strong>

          <span className="steps-card__unit">
            шагов
          </span>
        </div>

        <div>
          <span className="steps-card__label">
            Дней с данными
          </span>

          <strong>
            {formatCount(
              steps?.days_with_data,
            )}
          </strong>
        </div>
      </div>

      <p className="steps-card__period">
        Период:{' '}

        {period
          ? `${formatDate(period.from)} — ${formatDate(period.to)}`
          : '—'}
      </p>
    </section>
  )
}