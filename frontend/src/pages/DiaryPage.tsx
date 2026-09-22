import { useState } from 'react'
import { useRefreshSubscription } from '../refresh/RefreshProvider'

function DiaryPage() {
  const [refreshCount, setRefreshCount] = useState(0)
  const [lastRefreshLabel, setLastRefreshLabel] = useState('еще не было')

  useRefreshSubscription(({ requestedAt }) => {
    setRefreshCount((count) => count + 1)
    setLastRefreshLabel(formatRefreshTime(requestedAt))
  })

  return (
    <section className="page-section">
      <h2>Дневник</h2>
      <p>Временная страница дневника. Формы и записи будут добавлены позже.</p>
      <p className="refresh-status">Обновлений: {refreshCount}</p>
      <p className="refresh-status">Последнее обновление: {lastRefreshLabel}</p>
    </section>
  )
}

function formatRefreshTime(timestamp: number) {
  return new Intl.DateTimeFormat('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(timestamp))
}

export default DiaryPage
