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

  return `${value.toLocaleString('ru-RU')} ${unit}`
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
      name: 'Белки',
      grams: nutrition.protein_g,
      calories: nutrition.protein_g * 4,
      cssColor: 'var(--nutrition-protein)',
    },
    {
      name: 'Жиры',
      grams: nutrition.fat_g,
      calories: nutrition.fat_g * 9,
      cssColor: 'var(--nutrition-fat)',
    },
    {
      name: 'Углеводы',
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
      className="nutrition-card"
      aria-label="Калории и БЖУ"
    >
      <h2 className="nutrition-card__title">
        Калории и БЖУ
      </h2>

      <p className="nutrition-card__energy">
        {formatValue(
          nutrition?.energy_kcal,
          'ккал',
        )}
      </p>

      {hasMacroChart ? (
        <div className="nutrition-card__macro-layout">
          <div
            className="nutrition-card__chart"
            aria-label="Соотношение белков, жиров и углеводов"
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
              <strong>БЖУ</strong>
              <span>состав</span>
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
                      {formatValue(item.grams, 'г')}
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
            <span>Белки</span>
            <strong>
              {formatValue(
                nutrition?.protein_g,
                'г',
              )}
            </strong>
          </div>

          <div>
            <span>Жиры</span>
            <strong>
              {formatValue(
                nutrition?.fat_g,
                'г',
              )}
            </strong>
          </div>

          <div>
            <span>Углеводы</span>
            <strong>
              {formatValue(
                nutrition?.carbs_g,
                'г',
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
          Данные о рационе неполные.
          Показаны только записанные показатели.
        </p>
      )}
    </section>
  )
}