import { useCallback, useEffect, useState } from 'react'
import { fetchDashboard } from './api'
import type { Dashboard } from './types'

// Free hosting takes up to a minute to wake the backend, so a first request
// that fails is tried again for a while before giving up.
const RETRY_EVERY_MS = 3_000
const MAX_ATTEMPTS = 20

export type DashboardState =
  | { status: 'loading'; dashboard: null }
  | { status: 'ready'; dashboard: Dashboard }
  | { status: 'failed'; dashboard: null }

/** The player's dashboard, fetched when the screen opens and again on request. */
export function useDashboard() {
  const [state, setState] = useState<DashboardState>({ status: 'loading', dashboard: null })
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let disposed = false
    let retry: ReturnType<typeof setTimeout> | undefined
    let attempts = 0

    const load = () => {
      attempts++
      fetchDashboard().then(
        (dashboard) => {
          if (!disposed) setState({ status: 'ready', dashboard })
        },
        () => {
          if (disposed) return
          if (attempts < MAX_ATTEMPTS) retry = setTimeout(load, RETRY_EVERY_MS)
          else setState({ status: 'failed', dashboard: null })
        },
      )
    }
    load()

    return () => {
      disposed = true
      clearTimeout(retry)
    }
  }, [version])

  const refresh = useCallback(() => setVersion((current) => current + 1), [])

  return { ...state, refresh }
}
