import { useEffect, useRef, useState } from 'react'
import { runAfter, runMoment, type Moment } from './moments'

/**
 * Counts the rounds won one after another at a blackjack table, and says when
 * the run is long enough to celebrate. [net] is what the round numbered
 * [roundNumber] came to, once that has been shown, and null until then. The
 * count is for this sitting: leaving the table ends it.
 */
export function useWinRun(roundNumber: number | null, net: number | null): Moment | null {
  const counted = useRef<{ round: number | null; run: number }>({ round: null, run: 0 })
  const [moment, setMoment] = useState<Moment | null>(null)

  useEffect(() => {
    if (roundNumber === null || net === null || counted.current.round === roundNumber) return
    const run = runAfter(counted.current.run, net)
    counted.current = { round: roundNumber, run }
    const reached = runMoment(run, roundNumber)
    if (reached) setMoment(reached)
  }, [roundNumber, net])

  return moment
}
