import { useEffect, useState } from 'react'

/** Whole seconds until [endsAt], kept current while it is on screen. */
export function useSecondsUntil(endsAt: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(timer)
  }, [endsAt])
  return Math.max(0, Math.ceil((endsAt - now) / 1000))
}
