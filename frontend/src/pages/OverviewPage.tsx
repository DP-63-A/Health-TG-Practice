import { useState } from 'react'
import { Card, StateView } from '../components/ui'
import { useRefreshSubscription } from '../refresh/RefreshProvider'

function OverviewPage() {
  const [refreshCount, setRefreshCount] = useState(0)
  const [lastRefreshLabel, setLastRefreshLabel] = useState('еще не было')

  useRefreshSubscription(({ requestedAt }) => {
    setRefreshCount((count) => count + 1)
    setLastRefreshLabel(formatRefreshTime(requestedAt))
  })

  return (
    <Card
      subtitle="Временная страница обзора. Аналитика будет добавлена позже."
      title="Обзор"
    >
      <StateView
        message="Базовая область обзора подключена к общим состояниям; аналитические виджеты добавит FE2."
        title="Обзор готов"
        variant="success"
      />
      <p className="refresh-status">Обновлений: {refreshCount}</p>
      <p className="refresh-status">Последнее обновление: {lastRefreshLabel}</p>
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
