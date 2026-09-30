import { useId } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import type { AnalyticsResponse } from '../../../overview/analytics.types'

import {
  formatChartDate,
  formatSteps,
  formatTooltipDate,
} from '../../../overview/chartFormat'

import './StepsChart.css'

interface StepsChartProps {
  series: AnalyticsResponse['series']['steps']
  onSelectDay?: (date: string) => void
}

export function StepsChart({
  series,
  onSelectDay,
}: StepsChartProps) {
  const titleId = useId()

  const hasValues = series.some(
    (point) => point.value !== null,
  )

  function selectDay(date: string) {
    const point = series.find(
      (item) => item.date === date,
    )

    if (point) {
      onSelectDay?.(point.date)
    }
  }

  return (
    <section
      className="steps-chart"
      aria-labelledby={titleId}
    >
      <div className="steps-chart__header">
        <div>
          <h2 id={titleId}>
            Шаги
          </h2>

          <p className="steps-chart__description">
            Дневные итоги шагов
          </p>
        </div>

        <div
          className="steps-chart__icon"
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

      {!hasValues ? (
        <div
          className="steps-chart__empty"
          role="status"
        >
          <span
            className="steps-chart__empty-icon"
            aria-hidden="true"
          >
            —
          </span>

          <p>
            Нет данных о шагах за выбранный
            период.
          </p>
        </div>
      ) : (
        <>
          <div className="steps-chart__unit">
            шаги
          </div>

          <div className="steps-chart__plot">
            <ResponsiveContainer
              width="100%"
              height="100%"
              minWidth={0}
            >
              <BarChart
                data={series}
                accessibilityLayer
                margin={{
                  top: 8,
                  right: 4,
                  bottom: 4,
                  left: 0,
                }}
              >
                <CartesianGrid
                  stroke="currentColor"
                  strokeOpacity={0.08}
                  vertical={false}
                />

                <XAxis
                  dataKey="date"
                  tickFormatter={formatChartDate}
                  interval="preserveStartEnd"
                  minTickGap={16}
                  axisLine={false}
                  tickLine={false}
                  tick={{
                    fill: 'currentColor',
                    fontSize: 11,
                  }}
                  tickMargin={10}
                />

                <YAxis
                  domain={[0, 'auto']}
                  tickFormatter={formatSteps}
                  allowDecimals={false}
                  width={74}
                  tickCount={4}
                  axisLine={false}
                  tickLine={false}
                  tick={{
                    fill: 'currentColor',
                    fontSize: 11,
                  }}
                />

                <Tooltip
                  cursor={{
                    fill: 'currentColor',
                    fillOpacity: 0.035,
                  }}
                  content={({
                    active,
                    label,
                  }) => {
                    if (
                      !active ||
                      typeof label !== 'string'
                    ) {
                      return null
                    }

                    const point = series.find(
                      (item) =>
                        item.date === label,
                    )

                    if (
                      !point ||
                      point.value === null
                    ) {
                      return null
                    }

                    return (
                      <div className="steps-chart__tooltip">
                        <span className="steps-chart__tooltip-date">
                          {formatTooltipDate(
                            point.date,
                          )}
                        </span>

                        <div className="steps-chart__tooltip-value">
                          <span
                            className="steps-chart__tooltip-dot"
                            aria-hidden="true"
                          />

                          <strong>
                            {formatSteps(
                              point.value,
                            )}
                          </strong>
                        </div>
                      </div>
                    )
                  }}
                />

                <Bar
                  dataKey="value"
                  name="Шаги"
                  fill="var(--steps-chart-color)"
                  radius={[8, 8, 3, 3]}
                  maxBarSize={34}
                  isAnimationActive={false}
                  onClick={(barData) => {
                    const date =
                      barData.payload?.date

                    if (
                      typeof date === 'string'
                    ) {
                      selectDay(date)
                    }
                  }}
                />

                {series
                  .filter(
                    (point) =>
                      point.value === 0,
                  )
                  .map((point) => (
                    <ReferenceDot
                      key={point.date}
                      x={point.date}
                      y={0}
                      r={4}
                      fill="var(--steps-chart-color)"
                      stroke="currentColor"
                      onClick={() =>
                        selectDay(point.date)
                      }
                    />
                  ))}
              </BarChart>
            </ResponsiveContainer>
          </div>

          {onSelectDay && (
            <p className="steps-chart__hint">
              Нажмите на столбец, чтобы открыть
              записи за выбранный день
            </p>
          )}
        </>
      )}

      {series.length > 0 && (
        <details className="steps-chart__details">
          <summary>
            Значения по дням
          </summary>

          <div className="steps-chart__table-wrapper">
            <table>
              <caption>
                Дневные итоги шагов по локальным
                датам
              </caption>

              <thead>
                <tr>
                  <th scope="col">
                    Дата
                  </th>

                  <th scope="col">
                    Шаги
                  </th>
                </tr>
              </thead>

              <tbody>
                {series.map((point) => (
                  <tr key={point.date}>
                    <th scope="row">
                      {onSelectDay ? (
                        <button
                          type="button"
                          onClick={() =>
                            selectDay(
                              point.date,
                            )
                          }
                          aria-label={[
                            'Выбрать день',
                            formatTooltipDate(
                              point.date,
                            ),
                            point.value === null
                              ? 'Нет данных'
                              : formatSteps(
                                  point.value,
                                ),
                          ].join(' ')}
                        >
                          {formatTooltipDate(
                            point.date,
                          )}
                        </button>
                      ) : (
                        formatTooltipDate(
                          point.date,
                        )
                      )}
                    </th>

                    <td>
                      {point.value === null
                        ? 'Нет данных'
                        : formatSteps(
                            point.value,
                          )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  )
}