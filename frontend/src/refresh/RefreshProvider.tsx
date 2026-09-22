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

interface RefreshEvent {
  requestedAt: number
  version: number
}

interface RefreshContextValue {
  lastRefreshAt: number | null
  refreshVersion: number
  requestRefresh: () => void
}

const RefreshContext = createContext<RefreshContextValue | null>(null)

export function RefreshProvider({ children }: { children: ReactNode }) {
  const [refreshEvent, setRefreshEvent] = useState<RefreshEvent>({
    requestedAt: 0,
    version: 0,
  })

  const requestRefresh = useCallback(() => {
    setRefreshEvent((current) => ({
      requestedAt: Date.now(),
      version: current.version + 1,
    }))
  }, [])

  const value = useMemo(
    () => ({
      lastRefreshAt:
        refreshEvent.version === 0 ? null : refreshEvent.requestedAt,
      refreshVersion: refreshEvent.version,
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
  const { lastRefreshAt, refreshVersion } = useRefresh()
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
    })
  }, [lastRefreshAt, refreshVersion])
}
