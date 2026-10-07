import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useState, type CSSProperties } from 'react'
import { Button } from './ui'
import { useRefresh } from '../refresh/RefreshProvider'

function AppLayout() {
  const { requestRefresh } = useRefresh()

  const location = useLocation()
  const [sortingFor, setSortingFor] = useState<string | null>(null)
  const diaryRoute = location.pathname === '/diary'
  const diaryLocation = `${location.pathname}${location.search}`
  const filtersOpen = diaryRoute && sortingFor === diaryLocation
  const navigationState = location.state as
    | { overviewReturnTo?: unknown }
    | null

  const overviewReturnTo =
    typeof navigationState?.overviewReturnTo ===
    'string' &&
    /^\/overview(?:\?[^#]*)?$/.test(
      navigationState.overviewReturnTo,
    )
      ? navigationState.overviewReturnTo
      : '/overview'


  return (
    <div className="app-shell">
      <div className="notebook-page">
        <div className="notebook-sheet-stack" aria-hidden="true">
          {Array.from({ length: 4 }, (_, index) => (
            <span
              className="notebook-sheet"
              key={index}
              style={{ '--sheet-index': 4 - index } as CSSProperties}
            />
          ))}
        </div>
        <header className={`app-header${location.pathname === '/overview' ? ' app-header--stats' : ''}`}>
          <div className="app-title">
            <svg width="0" height="0" aria-hidden="true" focusable="false">
              <defs>
                <filter id="diary-deboss" colorInterpolationFilters="sRGB">
                  <feOffset in="SourceAlpha" dy="0.8" result="lowered" />
                  <feComposite in="SourceAlpha" in2="lowered" operator="out" result="top-edge" />
                  <feFlood floodColor="#201008" floodOpacity="0.6" />
                  <feComposite in2="top-edge" operator="in" />
                  <feComposite in2="SourceGraphic" operator="over" result="engraved" />
                  <feOffset in="SourceAlpha" dy="-0.6" result="raised" />
                  <feComposite in="SourceAlpha" in2="raised" operator="out" result="bottom-edge" />
                  <feFlood floodColor="#fff4dc" floodOpacity="0.3" />
                  <feComposite in2="bottom-edge" operator="in" />
                  <feComposite in2="engraved" operator="over" />
                </filter>
              </defs>
            </svg>
            <h1>
              {diaryRoute ? (
                <button className="diary-title-toggle" type="button" aria-label="Diary filters" aria-expanded={filtersOpen} aria-controls="diary-filters" onClick={() => setSortingFor(filtersOpen ? null : diaryLocation)}>
                  <span className="diary-title">Diary</span>
                  <span className="diary-title-chevron" aria-hidden="true">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" focusable="false"><path d="m6 9 6 6 6-6" /></svg>
                  </span>
                </button>
              ) : <span className="diary-title">{location.pathname === '/overview' ? 'Stats' : 'Diary'}</span>}
            </h1>
          </div>
          <div className="app-actions">
            <nav className="app-nav" aria-label="Main navigation">
              <NavLink
                className="bookmark bookmark--diary"
                aria-label="Diary"
                title="Diary"
                to="/diary"
                state={{
                  overviewReturnTo: location.pathname === '/overview'
                    ? `${location.pathname}${location.search}`
                    : overviewReturnTo,
                }}
              ><BookmarkIcon name="home" /></NavLink>
              <NavLink className="bookmark bookmark--overview" to={overviewReturnTo} aria-label="Overview" title="Overview">
                <BookmarkIcon name="search" />
              </NavLink>
            </nav>
            <Button className="bookmark bookmark--refresh" onClick={() => requestRefresh()} aria-label="Refresh" title="Refresh">
              <BookmarkIcon name="refresh" />
            </Button>
          </div>
        </header>
        <main className="app-main">
          <Outlet context={{ filtersOpen }} />
        </main>
      </div>
    </div>
  )
}

function BookmarkIcon({ name }: { name: 'home' | 'search' | 'refresh' }) {
  return (
    <svg className="bookmark-icon" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {name === 'home' && <path d="m3 10 9-7 9 7M5 8.5V21h5v-7h4v7h5V8.5" />}
      {name === 'search' && <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m15.2 15.2 5.3 5.3" /></>}
      {name === 'refresh' && <>
        <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5" />
        <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5" />
      </>}
    </svg>
  )
}

export default AppLayout
