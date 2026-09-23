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
    const hasValues = series.some((point) =>
    point.value !== null)

    function selectDay(date: string) {
      const point = series.find((item) => item.date
      === date)

      if (point && point.value !== null) {
        onSelectDay?.(point.date)
      }
    }

    return (
      <section
        className="nutrition-chart"
        aria-labelledby={titleId}
      >
        <h2 id={titleId}>Питание</h2>

        <p className="nutrition-chart__description">
          Записанные калории по дням, ккал
        </p>

        {!hasValues ? (
          <p role="status">Нет данных о питании за
          выбранный период.</p>
        ) : (
          <>
            <div className="nutrition-chart__plot">
              <ResponsiveContainer width="100%"
              height="100%" minWidth={0}>
                <BarChart
                  data={series}
                  accessibilityLayer
                  margin={{ top: 12, right: 12,
                  bottom: 8, left: 0 }}
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
                    tick={{ fill: 'currentColor',
                    fontSize: 12 }}
                    tickLine={false}
                  />

                  <YAxis
                    domain={[0, 'auto']}
                    width={52}
                    tickCount={5}
                    tick={{ fill: 'currentColor',
                    fontSize: 12 }}
                    tickLine={false}
                  />

                  <Tooltip
                    cursor={{ fill: 'currentColor',
                    fillOpacity: 0.06 }}
                    content={({ active, label }) => {
                      if (!active || typeof label !==
                      'string') {
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
                        <div className="nutrition-chart__tooltip">
                          <strong>

                            {formatTooltipDate(point.date)}
                          </strong>

                          <span>{formatCalories(point.value)}</span>
                        </div>
                      )
                    }}
                  />

                  <Bar
                    dataKey="value"
                    name="Калории"
                    fill="var(--nutrition-chart-color)"
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
                        fill="var(--nutrition-chart-color)"
                        stroke="currentColor"
                        onClick={() =>
                        selectDay(point.date)}
                      />
                    ))}
                </BarChart>
              </ResponsiveContainer>
            </div>

            <details className="nutrition-chart__details">
              <summary>Значения по дням</summary>

              <table>
                <caption>Записанные калории по
                локальным датам</caption>
                <thead>
                  <tr>
                    <th scope="col">Дата</th>
                    <th scope="col">Калории</th>
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
                            aria-label={
                              `Выбрать день
                              ${formatTooltipDate(point.date)}: ` +

                              formatCalories(point.value)
                            }
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
                          :
                          formatCalories(point.value)
                          }
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