import {
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from 'recharts'

import type { AnalyticsResponse } from '../../overview/analytics.types'

import './NutritionCard.css'

type Nutrition = AnalyticsResponse['cards']['nutrition']

interface NutritionCardProps {
  nutrition: Nutrition | null
}

interface MacroItem {
  name: string
  grams: number
  calories: number
  cssColor: string
}

function formatValue(
  value: number | null | undefined,
  unit: string,
): string {
  if (value == null) {
    return `— ${unit}`
  }

  return `${value.toLocaleString('en-GB')} ${unit}`
}

function getMacroData(
  nutrition: Nutrition | null,
): MacroItem[] {
  if (
    nutrition?.protein_g == null ||
    nutrition.fat_g == null ||
    nutrition.carbs_g == null
  ) {
    return []
  }

  return [
    {
      name: 'Protein',
      grams: nutrition.protein_g,
      calories: nutrition.protein_g * 4,
      cssColor: 'var(--nutrition-protein)',
    },
    {
      name: 'Fat',
      grams: nutrition.fat_g,
      calories: nutrition.fat_g * 9,
      cssColor: 'var(--nutrition-fat)',
    },
    {
      name: 'Carbs',
      grams: nutrition.carbs_g,
      calories: nutrition.carbs_g * 4,
      cssColor: 'var(--nutrition-carbs)',
    },
  ]
}

export function NutritionCard({
  nutrition,
}: NutritionCardProps) {
  const macroData = getMacroData(nutrition)

  const totalMacroCalories = macroData.reduce(
    (sum, item) => sum + item.calories,
    0,
  )

  const hasMacroChart = totalMacroCalories > 0

  return (
    <section
      className="nutrition-card paper-note"
      aria-label="Calories & macros"
    >
      <div className="nutrition-card__header">
        <h2 className="nutrition-card__title">
          Calories & macros
        </h2>
        <div className="nutrition-card__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" focusable="false">
            <path d="m13 2-8 12h6l-1 8 8-12h-6z" />
          </svg>
        </div>
      </div>

      <p className="nutrition-card__energy">
        {formatValue(
          nutrition?.energy_kcal,
          'kcal',
        )}
      </p>

      {hasMacroChart ? (
        <div className="nutrition-card__macro-layout">
          <div
            className="nutrition-card__chart"
            aria-label="Protein, fat and carbohydrate ratio"
          >
            <ResponsiveContainer
              width="100%"
              height="100%"
              minWidth={0}
            >
              <PieChart>
                <Pie
                  data={macroData}
                  dataKey="calories"
                  nameKey="name"
                  cx="50%"
                  cy="50%"
                  innerRadius="55%"
                  outerRadius="88%"
                  paddingAngle={2}
                  stroke="none"
                  isAnimationActive={false}
                >
                  {macroData.map((item) => (
                    <Cell
                      key={item.name}
                      fill={item.cssColor}
                    />
                  ))}
                </Pie>

                <Tooltip
                  formatter={(
                    value,
                    _name,
                    item,
                  ) => {
                    const calories = Number(value)
                    const percent =
                      totalMacroCalories > 0
                        ? Math.round(
                            (calories /
                              totalMacroCalories) *
                              100,
                          )
                        : 0

                    return [
                      `${percent}%`,
                      item.payload.name,
                    ]
                  }}
                />
              </PieChart>
            </ResponsiveContainer>

            <div className="nutrition-card__chart-center">
              <strong>Macros</strong>
              <span>breakdown</span>
            </div>
          </div>

          <div className="nutrition-card__legend">
            {macroData.map((item) => {
              const percent = Math.round(
                (item.calories /
                  totalMacroCalories) *
                  100,
              )

              return (
                <div
                  className="nutrition-card__legend-item"
                  key={item.name}
                >
                  <span
                    className="nutrition-card__legend-dot"
                    style={{
                      background: item.cssColor,
                    }}
                    aria-hidden="true"
                  />

                  <div>
                    <span className="nutrition-card__legend-name">
                      {item.name}
                    </span>

                    <strong>
                      {formatValue(item.grams, 'g')}
                    </strong>

                    <span className="nutrition-card__legend-percent">
                      {percent}%
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      ) : (
        <div className="nutrition-card__nutrients">
          <div>
            <span>Protein</span>
            <strong>
              {formatValue(
                nutrition?.protein_g,
                'g',
              )}
            </strong>
          </div>

          <div>
            <span>Fat</span>
            <strong>
              {formatValue(
                nutrition?.fat_g,
                'g',
              )}
            </strong>
          </div>

          <div>
            <span>Carbs</span>
            <strong>
              {formatValue(
                nutrition?.carbs_g,
                'g',
              )}
            </strong>
          </div>
        </div>
      )}

      {nutrition?.incomplete && (
        <p
          className="nutrition-card__warning"
          role="status"
        >
          Nutrition data is incomplete.
          Only recorded values are shown.
        </p>
      )}
    </section>
  )
}
