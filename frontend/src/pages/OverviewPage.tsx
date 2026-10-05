import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom'
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

import type {
    DiaryDrilldown,
    DiaryNavigationState,
  } from '../api/types'

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

///////
function parsePeriod(value: string | null): AnalyticsPeriod {
  if (
    value === 'today' ||
    value === 'days_7' ||
    value === 'days_21'
  ) {
    return value
  }

  return 'days_7'
}

function parseCategory(value: string | null): CheckinCategory {
  if (
    value === 'sleep_quality' ||
    value === 'digestion_comfort' ||
    value === 'wellbeing' ||
    value === 'mood'
  ) {
    return value
  }

  return 'mood'
}
//////////

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
  const location = useLocation()
  
  function openDiary(date: string, kind: ChartKind) {
    if (
      analyticsState.status !== 'success' &&
      analyticsState.status !== 'empty'
    ) {
      return
    }

    const data = analyticsState.data

    if (
      data.period.kind !== period ||
      (timezone && data.period.timezone !== timezone)
    ) {
      return
    }

    let drilldown: DiaryDrilldown

    if (kind === 'nutrition') {
      const point = data.series.nutrition.find(
        (item) => item.date === date,
      )

      if (!point) return

      drilldown = {
        kind,
        date: point.date,
        sourceIds: point.source.map((source) =>
        source.entry_id),
        hasValue: point.energy_kcal !== null,
      }
    } else if (kind === 'checkin') {
      const series = data.series.checkin

      if (series.category !== checkinCategory) return

      const point = series.points.find(
        (item) => item.date === date,
      )

      if (!point) return

      drilldown = {
        kind,
        date: point.date,
        category: series.category,
        sourceIds: point.source ?
        [point.source.entry_id] : [],
        hasValue: point.value !== null,
      }
    } else {
      const point = data.series[kind].find(
        (item) => item.date === date,
      )

      if (!point) return

      drilldown = {
        kind,
        date: point.date,
        sourceIds: point.source ?
        [point.source.entry_id] : [],
        hasValue: point.value !== null,
      }
    }

    drilldown.sourceIds = [...new
    Set(drilldown.sourceIds)]

    const returnParams = new
    URLSearchParams(location.search)
    returnParams.set('period', period)
    returnParams.set('checkin_category',
    checkinCategory)

    const state: DiaryNavigationState = {
      drilldown,
      overviewReturnTo: `/overview?${returnParams.toString()}`,
    }

    navigate(buildDiaryUrl(date, kind), { state }) 
  } 

  const [refreshCount, setRefreshCount] =
    useState(0)

  const [lastRefreshLabel, setLastRefreshLabel] =
    useState('еще не было')

  const [reloadKey, setReloadKey] =
    useState(0)

  const [searchParams, setSearchParams] = useSearchParams()

const period = parsePeriod(
  searchParams.get('period'),
)

const checkinCategory = parseCategory(
  searchParams.get('checkin_category'),
)

function updatePeriod(value: AnalyticsPeriod) {
  setSearchParams(
    (current) => {
      const next = new URLSearchParams(current)

      next.set('period', value)

      return next
    },
    { replace: true },
  )
}

function updateCategory(value: CheckinCategory) {
  setSearchParams(
    (current) => {
      const next = new URLSearchParams(current)

      next.set('checkin_category', value)

      return next
    },
    { replace: true },
  )
}

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

  const cardData =
  analyticsState.status === 'success' ||
  analyticsState.status === 'empty'
    ? analyticsState.data
    : null


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
    updatePeriod(event.target.value as AnalyticsPeriod)
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
    nutrition={cardData?.cards.nutrition ?? null}
  />

  <MealCountCard
    mealCount={cardData?.cards.meal_count ?? null}
  />

  <SleepCard
    sleep={cardData?.cards.sleep ?? null}
    period={cardData?.period ?? null}
  />

  <StepsCard
    steps={cardData?.cards.steps ?? null}
    period={cardData?.period ?? null}
  />

  <HeartRateCard
    heartRate={cardData?.cards.heart_rate ?? null}
    period={cardData?.period ?? null}
  />

  <CheckinCard
    checkins={cardData?.cards.checkins ?? null}
  />
</div>

      {/* Питание */}

 {(
    analyticsState.status === 'success' ||
    analyticsState.status === 'empty'
  ) && (
    <>
      <NutritionChart
        series={analyticsState.data.series.nutrition}
        onSelectDay={(date) => openDiary(date,
        'nutrition')}
      />

      <SleepChart
        series={analyticsState.data.series.sleep}
        onSelectDay={(date) => openDiary(date,
        'sleep')}
      />

      <StepsChart
        series={analyticsState.data.series.steps}
        onSelectDay={(date) => openDiary(date,
        'steps')}
      />

      <CheckinChart
        series={analyticsState.data.series.checkin}
        selectedCategory={checkinCategory}
        onCategoryChange={updateCategory}
        onSelectDay={(date) => openDiary(date,
        'checkin')}
      />
    </>
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
