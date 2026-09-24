import { useEffect, useState } from 'react'

import { Card, StateView } from '../components/ui'

import { NutritionCard } from '../components/Overview-components/NutritionCard'
import { StepsCard } from '../components/Overview-components/StepsCard'
import { SleepCard } from '../components/Overview-components/SleepCard'
import { MealCountCard } from '../components/Overview-components/MealCountCard'
import { HeartRateCard } from '../components/Overview-components/HeartRateCard'
import { CheckinCard } from '../components/Overview-components/CheckinCard'
import './OverviewPage.css'


import { useRefreshSubscription } from '../refresh/RefreshProvider'

import { useAuth } from '../auth/AuthProvider'

import { ApiError } from '../api/client'

import { getAnalytics } from '../overview/analytics'

import type { AnalyticsResponse } from '../overview/analytics.types'

// Возможные состояния загрузки аналитики

type AnalyticsState =
  | { status: 'loading' }
  | { status: 'success'; data: AnalyticsResponse }
  | { status: 'empty' }
  | { status: 'error' }

function OverviewPage() {
  const { markSessionExpired } = useAuth()

  const [analyticsState, setAnalyticsState] =
    useState<AnalyticsState>({
      status: 'loading',
    })

  const [refreshCount, setRefreshCount] = useState(0)

  const [lastRefreshLabel, setLastRefreshLabel] =
    useState('еще не было')

  const [reloadKey, setReloadKey] = useState(0)

  // Общий механизм обновления FE1

  useRefreshSubscription(({ requestedAt }) => {
    setRefreshCount((count) => count + 1)

    setLastRefreshLabel(
      formatRefreshTime(requestedAt),
    )

    // При получении refresh-сигнала
    // повторно запрашиваем аналитику

    setReloadKey((key) => key + 1)
  })

  // Получение аналитики

  useEffect(() => {
    let isActive = true

    async function loadAnalytics() {
      setAnalyticsState({
        status: 'loading',
      })

      try {
        const data = await getAnalytics({
          period: 'days_7',
        })

        if (!isActive) {
          return
        }

        // За выбранный период нет данных

        if (
          data.observations.days_with_any_data === 0
        ) {
          setAnalyticsState({
            status: 'empty',
          })

          return
        }

        // Аналитика успешно получена

        setAnalyticsState({
          status: 'success',
          data,
        })
      } catch (error) {
        if (!isActive) {
          return
        }

        // Передаём истечение сессии
        // общему AuthProvider FE1

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

  // Повторный запрос после ошибки

  function retryAnalytics() {
    setReloadKey((key) => key + 1)
  }

  return (
    <Card
      title="Обзор"
      subtitle="Аналитика за выбранный период"
    >

      {/* Загрузка */}

      {analyticsState.status === 'loading' && (
        <StateView
          title="Загрузка аналитики"
          message="Получаем данные за выбранный период."
          variant="loading"
        />
      )}

      {/* Нет данных */}

      {analyticsState.status === 'empty' && (
        <StateView
          title="Нет данных"
          message="За выбранный период нет записей для аналитики."
          variant="empty"
        />
      )}

      {/* Ошибка */}

      {analyticsState.status === 'error' && (
        <StateView
          title="Ошибка загрузки"
          message="Не удалось получить аналитику. Попробуйте ещё раз."
          variant="error"
          actionLabel="Повторить"
          onAction={retryAnalytics}
        />
      )}

      {/* Успешный ответ */}


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
