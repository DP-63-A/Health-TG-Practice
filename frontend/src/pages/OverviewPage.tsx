import { useEffect, useState } from 'react'

import { Card, StateView } from '../components/ui'

import { NutritionCard } from '../components/Overview-components/NutritionCard'
import { StepsCard } from '../components/Overview-components/StepsCard'
import { SleepCard } from '../components/Overview-components/SleepCard'
import { MealCountCard } from '../components/Overview-components/MealCountCard'
import { HeartRateCard } from '../components/Overview-components/HeartRateCard'
import { CheckinCard } from '../components/Overview-components/CheckinCard'

import { NutritionChart } from '../components/Overview-components/charts/NutritionChart'
import { SleepChart } from '../components/Overview-components/charts/SleepChart'
import { StepsChart } from '../components/Overview-components/charts/StepsChart'
import { CheckinChart } from '../components/Overview-components/charts/CheckinChart'

import { useRefreshSubscription } from '../refresh/RefreshProvider'
import { useAuth } from '../auth/AuthProvider'
import { ApiError } from '../api/client'

import { getAnalytics } from '../overview/analytics'

import type {
  AnalyticsResponse,
  CheckinCategory,
} from '../overview/analytics.types'

import './OverviewPage.css'

type AnalyticsState =
  | { status: 'loading' }
  | { status: 'success'; data: AnalyticsResponse }
  | { status: 'empty'; data: AnalyticsResponse }
  | { status: 'error' }

// Внутренний тип выбора точки.
// Это ещё НЕ контракт URL для перехода в Diary.

type ChartSelection =
  | {
      date: string
      kind: 'nutrition'
    }
  | {
      date: string
      kind: 'sleep'
    }
  | {
      date: string
      kind: 'steps'
    }
  | {
      date: string
      kind: 'checkin'
      category: CheckinCategory
    }

const chartLabels: Record<
  ChartSelection['kind'],
  string
> = {
  nutrition: 'Питание',
  sleep: 'Сон',
  steps: 'Шаги',
  checkin: 'Состояние',
}

const checkinLabels: Record<
  CheckinCategory,
  string
> = {
  sleep_quality: 'Качество сна',
  digestion_comfort: 'Комфорт пищеварения',
  wellbeing: 'Самочувствие',
  mood: 'Настроение',
}

function OverviewPage() {
  const { markSessionExpired } = useAuth()

  const [analyticsState, setAnalyticsState] =
    useState<AnalyticsState>({
      status: 'loading',
    })

  const [refreshCount, setRefreshCount] =
    useState(0)

  const [lastRefreshLabel, setLastRefreshLabel] =
    useState('еще не было')

  const [reloadKey, setReloadKey] =
    useState(0)

  // Новое: выбранная пользователем точка графика.

  const [selectedPoint, setSelectedPoint] =
    useState<ChartSelection | null>(null)

  // Общий механизм обновления FE1.

  useRefreshSubscription(({ requestedAt }) => {
    setRefreshCount((count) => count + 1)

    setLastRefreshLabel(
      formatRefreshTime(requestedAt),
    )

    setReloadKey((key) => key + 1)
  })

  // Получение аналитики.

  useEffect(() => {
    let isActive = true

    async function loadAnalytics() {
      setAnalyticsState({
        status: 'loading',
      })

      // Не сохраняем выбор из предыдущего ответа.
      setSelectedPoint(null)

      try {
        const data = await getAnalytics({
          period: 'days_7',
        })

        if (!isActive) {
          return
        }

        if (
          data.observations.days_with_any_data === 0
        ) {
          setAnalyticsState({
            status: 'empty',
             data,
          })

          return
        }

        setAnalyticsState({
          status: 'success',
          data,
        })
      } catch (error) {
        if (!isActive) {
          return
        }

        if (
          error instanceof ApiError &&
          error.status === 401
        ) {
          markSessionExpired()

          return
        }

        setAnalyticsState({
          status: 'error',
        })
      }
    }

    void loadAnalytics()

    return () => {
      isActive = false
    }
  }, [reloadKey, markSessionExpired])

  function retryAnalytics() {
    setReloadKey((key) => key + 1)
  }

  return (
    <Card
      title="Обзор"
      subtitle="Аналитика за выбранный период"
    >
      {analyticsState.status === 'loading' && (
        <StateView
          title="Загрузка аналитики"
          message="Получаем данные за выбранный период."
          variant="loading"
        />
      )}

      {analyticsState.status === 'empty' && (
        <StateView
          title="Нет данных"
          message="За выбранный период нет записей для аналитики."
          variant="empty"
        />
      )}

      {analyticsState.status === 'error' && (
        <StateView
          title="Ошибка загрузки"
          message="Не удалось получить аналитику. Попробуйте ещё раз."
          variant="error"
          actionLabel="Повторить"
          onAction={retryAnalytics}
        />
      )}

      <div className="overview-cards">
        <NutritionCard
          nutrition={
            analyticsState.status === 'success'
              ? analyticsState.data.cards.nutrition
              : null
          }
        />

        <MealCountCard
          mealCount={
            analyticsState.status === 'success'
              ? analyticsState.data.cards.meal_count
              : null
          }
        />

        <SleepCard
          sleep={
            analyticsState.status === 'success'
              ? analyticsState.data.cards.sleep
              : null
          }
          period={
            analyticsState.status === 'success'
              ? analyticsState.data.period
              : null
          }
        />

        <StepsCard
          steps={
            analyticsState.status === 'success'
              ? analyticsState.data.cards.steps
              : null
          }
          period={
            analyticsState.status === 'success'
              ? analyticsState.data.period
              : null
          }
        />

        <HeartRateCard
          heartRate={
            analyticsState.status === 'success'
              ? analyticsState.data.cards.heart_rate
              : null
          }
          period={
            analyticsState.status === 'success'
              ? analyticsState.data.period
              : null
          }
        />

        <CheckinCard
          checkins={
            analyticsState.status === 'success'
              ? analyticsState.data.cards.checkins
              : null
          }
        />
      </div>

      {/* Питание */}

      {analyticsState.status === 'success' && (
        <NutritionChart
          series={
            analyticsState.data.series.nutrition
          }
          onSelectDay={(date) => {
            setSelectedPoint({
              date,
              kind: 'nutrition',
            })
          }}
        />
      )}

      {analyticsState.status === 'empty' && (
        <NutritionChart series={[]} />
      )}

      {/* Сон */}

      {analyticsState.status === 'success' && (
        <SleepChart
          series={analyticsState.data.series.sleep}
          onSelectDay={(date) => {
            setSelectedPoint({
              date,
              kind: 'sleep',
            })
          }}
        />
      )}

      {analyticsState.status === 'empty' && (
        <SleepChart series={[]} />
      )}

      {/* Шаги */}

      {analyticsState.status === 'success' && (
        <StepsChart
          series={analyticsState.data.series.steps}
          onSelectDay={(date) => {
            setSelectedPoint({
              date,
              kind: 'steps',
            })
          }}
        />
      )}

      {analyticsState.status === 'empty' && (
        <StepsChart series={[]} />
      )}

      {/* Состояние */}

      {analyticsState.status === 'success' && (
        <CheckinChart
          series={analyticsState.data.series.checkin}
          onSelectDay={(date, category) => {
            setSelectedPoint({
              date,
              kind: 'checkin',
              category,
            })
          }}
        />
      )}


{analyticsState.status === 'empty' && (
  <CheckinChart
    series={analyticsState.data.series.checkin}
  />
)}


      {/* Временное отображение выбора для FE2-03.
          В FE2-04 вместо него будет переход в Diary. */}

      {analyticsState.status === 'success' &&
        selectedPoint && (
          <p role="status">
            Выбран день: {selectedPoint.date}.
            {' '}
            Показатель:{' '}
            {chartLabels[selectedPoint.kind]}.
            {selectedPoint.kind === 'checkin' && (
              <>
                {' '}
                Категория:{' '}
                {checkinLabels[
                  selectedPoint.category
                ]}.
              </>
            )}
          </p>
        )}

      {/* Отладочная информация FE1 */}

      <p className="refresh-status">
        Обновлений: {refreshCount}
      </p>

      <p className="refresh-status">
        Последнее обновление: {lastRefreshLabel}
      </p>
    </Card>
  )
}

function formatRefreshTime(timestamp: number) {
  return new Intl.DateTimeFormat('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(timestamp))
}

export default OverviewPage