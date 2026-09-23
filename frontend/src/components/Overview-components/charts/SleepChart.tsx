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
  }

  export function SleepChart({
    series,
    onSelectDay,
  }: SleepChartProps) {
    const titleId = useId()

    const hasValues = series.some(
      (point) => point.value !== null,
    )

    function selectDay(date: string) {
      const point = series.find(
        (item) => item.date === date,
      )

      if (point && point.value !== null) {
        onSelectDay?.(point.date)
      }
    }

    return (
      <section
        className="sleep-chart"
        aria-labelledby={titleId}
      >
        <h2 id={titleId}>Сон</h2>

        <p className="sleep-chart__description">
          Продолжительность сна по дате пробуждения
        </p>

        {!hasValues ? (
          <p role="status">
            Нет данных о сне за выбранный период.
          </p>
        ) : (
          <>
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
                    top: 12,
                    right: 12,
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
                    domain={[0, 'auto']}
                    tickFormatter={formatSleep}
                    allowDecimals={false}
                    width={88}
                    tickCount={4}
                    tick={{
                      fill: 'currentColor',
                      fontSize: 12,
                    }}
                    tickLine={false}
                  />

                  <Tooltip
                    cursor={{
                      fill: 'currentColor',
                      fillOpacity: 0.06,
                    }}
                    content={({ active, label }) => {
                      if (
                        !active ||
                        typeof label !== 'string'
                      ) {
                        return null
                      }

                      const point = series.find(
                        (item) => item.date ===
                        label,
                      )

                      if (!point || point.value ===
                      null) {
                        return null
                      }

                      return (
                        <div className="sleep-chart__tooltip">
                          <strong>

                            {formatTooltipDate(point.
                            date)}
                          </strong>

                          <span>

                            {formatSleep(point.value)
                            }
                          </span>
                        </div>
                      )
                    }}
                  />

                  <Bar
                    dataKey="value"
                    name="Продолжительность сна"
                    fill="var(--sleep-chart-color)"
                    maxBarSize={40}
                    isAnimationActive={false}
                    onClick={(barData) => {
  const date = barData.payload?.date

  if (typeof date === 'string') {
    selectDay(date)
  }
}}
                  />

                  {series
                    .filter((point) => point.value
                    === 0)
                    .map((point) => (
                      <ReferenceDot
                        key={point.date}
                        x={point.date}
                        y={0}
                        r={4}
                        fill="var(--sleep-chart-color)"
                        stroke="currentColor"
                        onClick={() =>
                        selectDay(point.date)}
                      />
                    ))}
                </BarChart>
              </ResponsiveContainer>
            </div>

            <details className="sleep-chart__details">
              <summary>Значения по дням</summary>

              <table>
                <caption>
                  Продолжительность сна по датам
                  пробуждения
                </caption>

                <thead>
                  <tr>
                    <th scope="col">Дата</th>
                    <th scope="col">Сон</th>
                  </tr>
                </thead>

                <tbody>
                  {series.map((point) => (
                    <tr key={point.date}>
                      <th scope="row">
                        {point.value !== null &&
                        onSelectDay ? (
                          <button
                            type="button"
                            onClick={() =>
                            selectDay(point.date)}
                            aria-label={[
                              'Выбрать день',

                              formatTooltipDate(point.date),
                              'сон:',

                              formatSleep(point.value
                              ),
                            ].join(' ')}
                          >

                            {formatTooltipDate(point.date)}
                          </button>
                        ) : (

                          formatTooltipDate(point.date)
                        )}
                      </th>

                      <td>
                        {point.value === null
                          ? 'Нет данных'
                          : formatSleep(point.value)}
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