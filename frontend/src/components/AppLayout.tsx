import { NavLink, Outlet } from 'react-router-dom'
import { apiMode } from '../api/client'
import { Badge, Button } from './ui'
import { useRefresh } from '../refresh/RefreshProvider'

function AppLayout() {
  const { requestRefresh } = useRefresh()

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <p className="app-kicker">Health TG Practice</p>
          <h1>Дневник здоровья</h1>
        </div>
        {apiMode === 'fixture' ? <Badge tone="neutral">Режим: fixture</Badge> : null}
        <div className="app-actions">
          <Button onClick={requestRefresh}>
            Обновить
          </Button>
          <nav className="app-nav" aria-label="Основная навигация">
            <NavLink to="/diary">Дневник</NavLink>
            <NavLink to="/overview">Обзор</NavLink>
          </nav>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}

export default AppLayout
