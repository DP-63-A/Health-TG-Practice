import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
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
  AnalyticsPeriod,
  CheckinCategory,
} from '../overview/analytics.types'

import './OverviewPage.css'

type AnalyticsState =
  | { status: 'loading' }
  | { status: 'success'; data: AnalyticsResponse }
  | { status: 'empty'; data: AnalyticsResponse }
  | { status: 'error' }


type ChartKind =
  | 'nutrition'
  | 'sleep'
  | 'steps'
  | 'checkin'

type DiaryEntryType =
  | 'meal'
  | 'metrics'
  | 'checkin'

const diaryEntryTypes: Record<ChartKind, DiaryEntryType> = {
  nutrition: 'meal',
  sleep: 'metrics',
  steps: 'metrics',
  checkin: 'checkin',
}

export function buildDiaryUrl(
  date: string,
  kind: ChartKind,
): string {
  const params = new URLSearchParams({
    from: date,
    to: date,
    type: diaryEntryTypes[kind],
  })

  return `/diary?${params.toString()}`
}

function OverviewPage() {
  const { state: authState, markSessionExpired } = useAuth()
  const timezone =
    authState?.status === 'authenticated'
      ? authState.user.timezone
      : undefined

  const [analyticsState, setAnalyticsState] =
    useState<AnalyticsState>({
      status: 'loading',
    })

  const navigate = useNavigate()

  function openDiary(
  date: string,
  kind: ChartKind,
) {
  navigate(buildDiaryUrl(date, kind))
}

  const [refreshCount, setRefreshCount] =
    useState(0)

  const [lastRefreshLabel, setLastRefreshLabel] =
    useState('еще не было')

  const [reloadKey, setReloadKey] =
    useState(0)

  const [period, setPeriod] =
    useState<AnalyticsPeriod>('days_7')

  const [checkinCategory, setCheckinCategory] =
    useState<CheckinCategory>('mood')

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

      try {
        const data = await getAnalytics({
          period,
          timezone,
          checkin_category: checkinCategory,
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
  }, [reloadKey, period, checkinCategory, timezone, markSessionExpired])

  function retryAnalytics() {
    setReloadKey((key) => key + 1)
  }

  return (
    <Card
      title="Обзор"
      subtitle="Аналитика за выбранный период"
    >
      <div className="overview-filters">
        <label htmlFor="overview-period">Период</label>
        <select
          id="overview-period"
          value={period}
          onChange={(event) => {
            setPeriod(event.target.value as AnalyticsPeriod)
          }}
        >
          <option value="today">Сегодня</option>
          <option value="days_7">7 дней</option>
          <option value="days_21">21 день</option>
        </select>
      </div>

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
    series={analyticsState.data.series.nutrition}
    onSelectDay={(date) => openDiary(date, 'nutrition')}
  />
)}

      {analyticsState.status === 'empty' && (
        <NutritionChart series={[]} />
      )}

      {/* Сон */}

      {analyticsState.status === 'success' && (
        <SleepChart
  series={analyticsState.data.series.sleep}
  onSelectDay={(date) => openDiary(date, 'sleep')}
/>
      )}

      {analyticsState.status === 'empty' && (
        <SleepChart series={[]} />
      )}

      {/* Шаги */}

      {analyticsState.status === 'success' && (
        <StepsChart
  series={analyticsState.data.series.steps}
  onSelectDay={(date) => openDiary(date, 'steps')}
/>
      )}

      {analyticsState.status === 'empty' && (
        <StepsChart series={[]} />
      )}

      {/* Состояние */}

      {analyticsState.status === 'success' && (
        <CheckinChart
  series={analyticsState.data.series.checkin}
  selectedCategory={checkinCategory}
  onCategoryChange={setCheckinCategory}
  onSelectDay={(date) => openDiary(date, 'checkin')}
/>
      )}


      {analyticsState.status === 'empty' && (
        <CheckinChart
          series={analyticsState.data.series.checkin}
          selectedCategory={checkinCategory}
          onCategoryChange={setCheckinCategory}
        />
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
