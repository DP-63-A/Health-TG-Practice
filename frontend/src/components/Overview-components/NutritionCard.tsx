
import type { AnalyticsResponse } from '../../overview/analytics.types'

import './NutritionCard.css'

type Nutrition = AnalyticsResponse['cards']['nutrition']

interface NutritionCardProps {
  nutrition: Nutrition | null
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

export function NutritionCard({
  nutrition,
}: NutritionCardProps) {
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