/* eslint-disable react-refresh/only-export-components */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'

export type RefreshReason = 'manual' | 'lifecycle' | 'mutation'

interface RefreshEvent {
  requestedAt: number
  version: number
  reason: RefreshReason
}

interface RefreshContextValue {
  lastRefreshAt: number | null
  refreshVersion: number
  refreshReason: RefreshReason | null
  requestRefresh: (reason?: RefreshReason) => void
}

const RefreshContext = createContext<RefreshContextValue | null>(null)

export function RefreshProvider({ children }: { children: ReactNode }) {
  const [refreshEvent, setRefreshEvent] = useState<RefreshEvent>({
    requestedAt: 0,
    version: 0,
    reason: 'manual',
  })

  const requestRefresh = useCallback((reason: RefreshReason = 'manual') => {
    setRefreshEvent((current) => ({
      requestedAt: Date.now(),
      version: current.version + 1,
      reason,
    }))
  }, [])

  useEffect(() => {
    let wasAway = false
    let lastResumeAt = 0
    let resumeTimer: ReturnType<typeof setTimeout> | null = null
    const markAway = () => { wasAway = true }
    const resume = () => {
      if (!wasAway || document.visibilityState === 'hidden' || resumeTimer !== null) return
      resumeTimer = setTimeout(() => {
        resumeTimer = null
        if (!wasAway || document.visibilityState === 'hidden') return
        wasAway = false
        if (Date.now() - lastResumeAt < 500) return
        lastResumeAt = Date.now()
        requestRefresh('lifecycle')
      }, 100)
    }
    const visibilityChanged = () => {
      if (document.visibilityState === 'hidden') markAway()
      else resume()
    }
    const pageShown = (event: PageTransitionEvent) => {
      if (event.persisted) {
        if (!wasAway && Date.now() - lastResumeAt < 500) return
        markAway()
        resume()
      }
    }

    document.addEventListener('visibilitychange', visibilityChanged)
    window.addEventListener('blur', markAway)
    window.addEventListener('focus', resume)
    window.addEventListener('pagehide', markAway)
    window.addEventListener('pageshow', pageShown)
    return () => {
      if (resumeTimer !== null) clearTimeout(resumeTimer)
      document.removeEventListener('visibilitychange', visibilityChanged)
      window.removeEventListener('blur', markAway)
      window.removeEventListener('focus', resume)
      window.removeEventListener('pagehide', markAway)
      window.removeEventListener('pageshow', pageShown)
    }
  }, [requestRefresh])

  const value = useMemo(
    () => ({
      lastRefreshAt:
        refreshEvent.version === 0 ? null : refreshEvent.requestedAt,
      refreshVersion: refreshEvent.version,
      refreshReason: refreshEvent.version === 0 ? null : refreshEvent.reason,
      requestRefresh,
    }),
    [refreshEvent, requestRefresh],
  )

  return (
    <RefreshContext.Provider value={value}>{children}</RefreshContext.Provider>
  )
}

export function useRefresh() {
  const context = useContext(RefreshContext)

  if (!context) {
    throw new Error('useRefresh must be used inside RefreshProvider.')
  }

  return context
}

export function useRefreshSubscription(
  onRefresh: (event: RefreshEvent) => void,
) {
  const { lastRefreshAt, refreshReason, refreshVersion } = useRefresh()
  const onRefreshRef = useRef(onRefresh)
  const didMountRef = useRef(false)

  useEffect(() => {
    onRefreshRef.current = onRefresh
  }, [onRefresh])

  useEffect(() => {
    if (!didMountRef.current) {
      didMountRef.current = true
      return
    }

    if (lastRefreshAt === null) {
      return
    }

    onRefreshRef.current({
      requestedAt: lastRefreshAt,
      version: refreshVersion,
      reason: refreshReason ?? 'manual',
    })
  }, [lastRefreshAt, refreshReason, refreshVersion])
}
