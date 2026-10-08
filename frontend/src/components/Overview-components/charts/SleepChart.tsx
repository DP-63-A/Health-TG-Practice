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
  formatSleep,
  formatTooltipDate,
} from '../../../overview/chartFormat'

import './SleepChart.css'

interface SleepChartProps {
  series: AnalyticsResponse['series']['sleep']
  onSelectDay?: (date: string) => void
  embedded?: boolean
}

export function SleepChart({
  series,
  onSelectDay,
  embedded = false,
}: SleepChartProps) {
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
      className={embedded ? 'sleep-chart' : 'sleep-chart paper-note'}
      aria-labelledby={embedded ? undefined : titleId}
      aria-label={embedded ? 'Sleep' : undefined}
    >
      {!embedded && <div className="sleep-chart__header">
        <div>
          <h2 id={titleId}>
            Sleep
          </h2>

          <p className="sleep-chart__description">
            Sleep duration by wake date
          </p>
        </div>

        <div
          className="sleep-chart__icon"
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
            <path d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5a8.5 8.5 0 1 0 10.7 10.7Z" />
          </svg>
        </div>
      </div>}

      {!hasValues ? (
        <div
          className="sleep-chart__empty"
          role="status"
        >
          <span
            className="sleep-chart__empty-icon"
            aria-hidden="true"
          >
            —
          </span>

          <p>
            No sleep data for the selected period.
          </p>
        </div>
      ) : (
        <>
          <div className="sleep-chart__unit">
            sleep duration
          </div>

          <div className="sleep-chart__plot">
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
                  tickFormatter={formatSleep}
                  allowDecimals={false}
                  width={72}
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
                      <div className="sleep-chart__tooltip">
                        <span className="sleep-chart__tooltip-date">
                          {formatTooltipDate(
                            point.date,
                          )}
                        </span>

                        <div className="sleep-chart__tooltip-value">
                          <span
                            className="sleep-chart__tooltip-dot"
                            aria-hidden="true"
                          />

                          <strong>
                            {formatSleep(
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
                  name="Sleep duration"
                  fill="var(--sleep-chart-color)"
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
                      fill="var(--sleep-chart-color)"
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
            <p className="sleep-chart__hint">
              Tap a bar to open entries for the selected day
            </p>
          )}
        </>
      )}

      {series.length > 0 && (
        <details className="sleep-chart__details">
          <summary>
            Daily values
          </summary>

          <div className="sleep-chart__table-wrapper">
            <table>
              <caption>
                Sleep duration by wake dates
              </caption>

              <thead>
                <tr>
                  <th scope="col">
                    Date
                  </th>

                  <th scope="col">
                    Sleep
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
                            'Select day',
                            formatTooltipDate(
                              point.date,
                            ),
                            'sleep:',
                            point.value === null
                              ? 'No data'
                              : formatSleep(
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
                        ? 'No data'
                        : formatSleep(
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
