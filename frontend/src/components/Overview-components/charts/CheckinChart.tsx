
import { useId } from 'react'

import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import type {
  AnalyticsResponse,
  CheckinCategory,
} from '../../../overview/analytics.types'

import {
  formatChartDate,
  formatTooltipDate,
} from '../../../overview/chartFormat'

import './CheckinChart.css'

const categories: {
  value: CheckinCategory
  label: string
}[] = [
  {
    value: 'sleep_quality',
    label: 'Качество сна',
  },
  {
    value: 'digestion_comfort',
    label: 'Комфорт пищеварения',
  },
  {
    value: 'wellbeing',
    label: 'Самочувствие',
  },
  {
    value: 'mood',
    label: 'Настроение',
  },
]

const categoryLabels: Record<CheckinCategory, string> = {
  sleep_quality: 'Качество сна',
  digestion_comfort: 'Комфорт пищеварения',
  wellbeing: 'Самочувствие',
  mood: 'Настроение',
}

interface CheckinChartProps {
  series: AnalyticsResponse['series']['checkin']
  selectedCategory?: CheckinCategory
  onCategoryChange?: (category: CheckinCategory) => void
  isLoading?: boolean
  onSelectDay?: (
    date: string,
    category: CheckinCategory,
  ) => void
}

function formatScore(value: number | null): string {
  return value === null ? 'Нет данных' : `${value} из 5`
}

export function CheckinChart({
  series,
  selectedCategory = series.category,
  onCategoryChange,
  isLoading = false,
  onSelectDay,
}: CheckinChartProps) {
  const titleId = useId()
  const selectId = useId()
  const scaleId = useId()

  const categoryLabel = categoryLabels[series.category]

  const matchesCategory =
    selectedCategory === series.category

  const hasValues = series.points.some(
    (point) => point.value !== null,
  )

  function selectDay(date: string) {
    if (isLoading || !matchesCategory) {
      return
    }

    const point = series.points.find(
      (item) => item.date === date,
    )

    if (point && point.value !== null) {
      onSelectDay?.(point.date, series.category)
    }
  }

  return (
    <section
      className="checkin-chart"
      aria-labelledby={titleId}
      aria-describedby={scaleId}
      aria-busy={isLoading}
    >
      <h2 id={titleId}>Состояние</h2>

      {onCategoryChange ? (
        <div className="checkin-chart__control">
          <label htmlFor={selectId}>
            Категория
          </label>

          <select
            id={selectId}
            value={selectedCategory}
            disabled={isLoading}
            onChange={(event) => {
              const category = categories.find(
                (item) => item.value === event.target.value,
              )

              if (category) {
                onCategoryChange(category.value)
              }
            }}
          >
            {categories.map((category) => (
              <option
                key={category.value}
                value={category.value}
              >
                {category.label}
              </option>
            ))}
          </select>
        </div>
      ) : (
        <p>{categoryLabel}</p>
      )}

      <p
        id={scaleId}
        className="checkin-chart__scale"
      >
        1 — очень плохо / очень низкий комфорт.
        5 — очень хорошо / высокий комфорт.
      </p>

      {isLoading ? (
        <p role="status">
          Загрузка оценок…
        </p>
      ) : !matchesCategory ? (
        <p role="status">
          Данные выбранной категории ещё не получены.
        </p>
      ) : !hasValues ? (
        <p role="status">
          Нет оценок «{categoryLabel}» за выбранный период.
        </p>
      ) : (
        <>
          <div className="checkin-chart__plot">
            <ResponsiveContainer
              width="100%"
              height="100%"
              minWidth={0}
            >
              <BarChart
                data={series.points}
                accessibilityLayer
                margin={{
                  top: 12,
                  right: 16,
                  bottom: 8,
                  left: 0,
                }}
              >
                <CartesianGrid
                  stroke="currentColor"
                  strokeOpacity={0.15}
                  strokeDasharray="3 3"
                  vertical={false}
                />

                <XAxis
                  dataKey="date"
                  tickFormatter={formatChartDate}
                  interval="preserveStartEnd"
                  minTickGap={16}
                  tick={{
                    fill: 'currentColor',
                    fontSize: 12,
                  }}
                  tickLine={false}
                />

                <YAxis
                  domain={[0, 5]}
                  ticks={[1, 2, 3, 4, 5]}
                  allowDecimals={false}
                  allowDataOverflow
                  width={32}
                  tick={{
                    fill: 'currentColor',
                    fontSize: 12,
                  }}
                  tickLine={false}
                />

                <Tooltip
                  content={({ active, label }) => {
                    if (
                      !active ||
                      typeof label !== 'string'
                    ) {
                      return null
                    }

                    const point = series.points.find(
                      (item) => item.date === label,
                    )

                    if (
                      !point ||
                      point.value === null
                    ) {
                      return null
                    }

                    return (
                      <div className="checkin-chart__tooltip">
                        <strong>
                          {formatTooltipDate(point.date)}
                        </strong>

                        <span>
                          {categoryLabel}
                        </span>

                        <span>
                          {formatScore(point.value)}
                        </span>
                      </div>
                    )
                  }}
                />

                <Bar
                  dataKey="value"
                  name={categoryLabel}
                  fill="var(--checkin-chart-color, #2563eb)"
                  radius={[4, 4, 0, 0]}
                  maxBarSize={42}
                  isAnimationActive={false}
                  onClick={(barData) => {
                    const date = barData.payload?.date

                    if (typeof date === 'string') {
                      selectDay(date)
                    }
                  }}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <details className="checkin-chart__details">
            <summary>
              Значения по дням
            </summary>

            <table>
              <caption>
                {categoryLabel}: оценки по дням
              </caption>

              <thead>
                <tr>
                  <th scope="col">
                    Дата
                  </th>

                  <th scope="col">
                    Оценка
                  </th>
                </tr>
              </thead>

              <tbody>
                {series.points.map((point) => (
                  <tr key={point.date}>
                    <th scope="row">
                      {point.value !== null &&
                      onSelectDay ? (
                        <button
                          type="button"
                          onClick={() =>
                            selectDay(point.date)
                          }
                          aria-label={[
                            'Выбрать день',
                            formatTooltipDate(point.date),
                            categoryLabel,
                            formatScore(point.value),
                          ].join(' ')}
                        >
                          {formatTooltipDate(point.date)}
                        </button>
                      ) : (
                        formatTooltipDate(point.date)
                      )}
                    </th>

                    <td>
                      {formatScore(point.value)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      )}
    </section>
  )
}