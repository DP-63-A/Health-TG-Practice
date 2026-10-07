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
  formatCalories,
  formatChartDate,
  formatTooltipDate,
} from '../../../overview/chartFormat'

import './NutritionChart.css'

interface NutritionChartProps {
  series: AnalyticsResponse['series']['nutrition']
  onSelectDay?: (date: string) => void
}

export function NutritionChart({
  series,
  onSelectDay,
}: NutritionChartProps) {
  const titleId = useId()

  const hasValues = series.some(
    (point) => point.energy_kcal !== null,
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
      className="nutrition-chart paper-note"
      aria-labelledby={titleId}
    >
      <div className="nutrition-chart__header">
        <div>
          <h2 id={titleId}>
            Nutrition
          </h2>

          <p className="nutrition-chart__description">
            Recorded calories by day
          </p>
        </div>

        <div
          className="nutrition-chart__icon"
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
              r="8"
            />

            <circle
              cx="12"
              cy="12"
              r="4"
            />

            <path d="M4 12h-2" />
            <path d="M22 12h-2" />
          </svg>
        </div>
      </div>

      {!hasValues ? (
        <div
          className="nutrition-chart__empty"
          role="status"
        >
          <span
            className="nutrition-chart__empty-icon"
            aria-hidden="true"
          >
            —
          </span>

          <p>
            No nutrition data for the selected period.
          </p>
        </div>
      ) : (
        <>
          <div className="nutrition-chart__unit">
            kcal
          </div>

          <div className="nutrition-chart__plot">
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
                  left: -8,
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
                  width={50}
                  tickCount={5}
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
                      point.energy_kcal === null
                    ) {
                      return null
                    }

                    return (
                      <div className="nutrition-chart__tooltip">
                        <span className="nutrition-chart__tooltip-date">
                          {formatTooltipDate(
                            point.date,
                          )}
                        </span>

                        <div className="nutrition-chart__tooltip-value">
                          <span
                            className="nutrition-chart__tooltip-dot"
                            aria-hidden="true"
                          />

                          <strong>
                            {formatCalories(
                              point.energy_kcal,
                            )}
                          </strong>
                        </div>
                      </div>
                    )
                  }}
                />

                <Bar
                  dataKey="energy_kcal"
                  name="Calories"
                  fill="var(--nutrition-chart-color)"
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
                      point.energy_kcal === 0,
                  )
                  .map((point) => (
                    <ReferenceDot
                      key={point.date}
                      x={point.date}
                      y={0}
                      r={4}
                      fill="var(--nutrition-chart-color)"
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
            <p className="nutrition-chart__hint">
              Tap a bar to open entries for the selected day
            </p>
          )}
        </>
      )}

      {series.length > 0 && (
        <details className="nutrition-chart__details">
          <summary>
            Daily values
          </summary>

          <div className="nutrition-chart__table-wrapper">
            <table>
              <caption>
                Recorded calories by local date
              </caption>

              <thead>
                <tr>
                  <th scope="col">
                    Date
                  </th>

                  <th scope="col">
                    Calories
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
                          aria-label={
                            `Select day ${formatTooltipDate(point.date)}: ` +
                            (point.energy_kcal ===
                            null
                              ? 'No data'
                              : formatCalories(
                                  point.energy_kcal,
                                ))
                          }
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
                      {point.energy_kcal ===
                      null
                        ? 'No data'
                        : formatCalories(
                            point.energy_kcal,
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
