import { useState } from 'react'

/**
 * How many of something there were before the latest ones arrived, within one
 * round, so that only the newcomers are dealt in. Kept as state adjusted
 * during render, which is how React asks for a value that depends on the
 * previous one.
 */
export function useCountBefore(count: number, round: number): number {
  const [seen, setSeen] = useState({ count, round, before: 0 })

  if (seen.count !== count || seen.round !== round) {
    const before = seen.round === round ? seen.count : 0
    setSeen({ count, round, before })
    return before
  }
  return seen.before
}
