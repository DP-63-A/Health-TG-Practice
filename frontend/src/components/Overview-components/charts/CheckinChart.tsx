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
    label: 'Sleep quality',
  },
  {
    value: 'digestion_comfort',
    label: 'Digestive comfort',
  },
  {
    value: 'wellbeing',
    label: 'Wellbeing',
  },
  {
    value: 'mood',
    label: 'Mood',
  },
]


const categoryLabels: Record<
  CheckinCategory,
  string
> = {

  sleep_quality: 'Sleep quality',
  digestion_comfort: 'Digestive comfort',
  wellbeing: 'Wellbeing',
  mood: 'Mood',
}

interface CheckinChartProps {
  series: AnalyticsResponse['series']['checkin']
  selectedCategory?: CheckinCategory

  onCategoryChange?: (
    category: CheckinCategory,
  ) => void

  isLoading?: boolean
  onSelectDay?: (
    date: string,
    category: CheckinCategory,
  ) => void
}


function formatScore(
  value: number | null,
): string {
  return value === null
    ? 'No data'
    : `${value} out of 5`

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


  const categoryLabel =
    categoryLabels[series.category]


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

    if (point) {
      onSelectDay?.(
        point.date,
        series.category,
      )

    }
  }

  return (
    <section
      className="checkin-chart paper-note"
      aria-labelledby={titleId}
      aria-busy={isLoading}
    >

      <div className="checkin-chart__header">
        <div>
          <h2 id={titleId}>
            Wellbeing
          </h2>

        </div>

        <div
          className="checkin-chart__icon"
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

            <path
              d="
                M8.5 14.5
                c1 1.2 2.1 1.8 3.5 1.8
                s2.5-.6 3.5-1.8
              "
            />
          </svg>
        </div>
      </div>


      {onCategoryChange ? (
        <div className="checkin-chart__control">
          <label htmlFor={selectId}>
            Category
          </label>


          <div className="checkin-chart__select-wrapper">
            <select
              id={selectId}
              value={selectedCategory}
              disabled={isLoading}
              onChange={(event) => {
                const category =
                  categories.find(
                    (item) =>
                      item.value ===
                      event.target.value,
                  )

                if (category) {
                  onCategoryChange(
                    category.value,
                  )
                }
              }}
            >
              {categories.map(
                (category) => (
                  <option
                    key={category.value}
                    value={category.value}
                  >
                    {category.label}
                  </option>
                ),
              )}
            </select>

            <span
              className="checkin-chart__select-icon"
              aria-hidden="true"
            >
              <svg
                viewBox="0 0 20 20"
                width="18"
                height="18"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="m6 8 4 4 4-4" />
              </svg>
            </span>
          </div>
        </div>
      ) : (
        <p className="checkin-chart__category">
          {categoryLabel}
        </p>
      )}

      {isLoading ? (
        <div
          className="checkin-chart__state"
          role="status"
        >
          <span
            className="checkin-chart__state-icon"
            aria-hidden="true"
          >
            …
          </span>

          <p>Loading scores…</p>
        </div>
      ) : !matchesCategory ? (
        <div
          className="checkin-chart__state"
          role="status"
        >
          <span
            className="checkin-chart__state-icon"
            aria-hidden="true"
          >
            —
          </span>

          <p>
            Data for the selected category has not arrived yet.
          </p>
        </div>
      ) : !hasValues ? (
        <div
          className="checkin-chart__state"
          role="status"
        >
          <span
            className="checkin-chart__state-icon"
            aria-hidden="true"
          >
            —
          </span>

          <p>
            No scores for “{categoryLabel}” for
            the selected period.
          </p>
        </div>

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

                  tickFormatter={
                    formatChartDate
                  }
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
                  domain={[0, 5]}
                  ticks={[1, 2, 3, 4, 5]}
                  allowDecimals={false}
                  allowDataOverflow

                  width={28}
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
                      typeof label !==
                        'string'

                    ) {
                      return null
                    }


                    const point =
                      series.points.find(
                        (item) =>
                          item.date ===
                          label,
                      )


                    if (
                      !point ||
                      point.value === null
                    ) {
                      return null
                    }

                    return (
                      <div className="checkin-chart__tooltip">

                        <span className="checkin-chart__tooltip-date">
                          {formatTooltipDate(
                            point.date,
                          )}
                        </span>

                        <span className="checkin-chart__tooltip-category">
                          {categoryLabel}
                        </span>

                        <div className="checkin-chart__tooltip-value">
                          <span
                            className="checkin-chart__tooltip-dot"
                            aria-hidden="true"
                          />

                          <strong>
                            {formatScore(
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
                  name={categoryLabel}

                  fill="var(--checkin-chart-color)"
                  radius={[8, 8, 3, 3]}
                  maxBarSize={34}
                  isAnimationActive={false}
                  onClick={(barData) => {
                    const date =
                      barData.payload?.date

                    if (
                      typeof date ===
                      'string'
                    ) {

                      selectDay(date)
                    }
                  }}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>


          {onSelectDay && (
            <p className="checkin-chart__hint">
              Tap a bar to open the entry for the selected day
            </p>
          )}
        </>
      )}

      {!isLoading &&
        matchesCategory &&
        series.points.length > 0 && (
          <details className="checkin-chart__details">

            <summary>
              Daily values
            </summary>


            <div className="checkin-chart__table-wrapper">
              <table>
                <caption>
                  {categoryLabel}: daily scores
                </caption>

                <thead>
                  <tr>
                    <th scope="col">
                      Date
                    </th>

                    <th scope="col">
                      Score
                    </th>
                  </tr>
                </thead>

                <tbody>
                  {series.points.map(
                    (point) => (
                      <tr
                        key={point.date}
                      >
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
                                categoryLabel,
                                formatScore(
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
                          {formatScore(
                            point.value,
                          )}
                        </td>
                      </tr>
                    ),
                  )}
                </tbody>
              </table>
            </div>
          </details>
        )}
    </section>
  )
}
