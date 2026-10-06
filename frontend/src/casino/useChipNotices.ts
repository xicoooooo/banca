import { useCallback, useEffect, useState } from 'react'
import type { Broke, ChipNotice } from '../player/types'
import { until } from '../profile/format'
import { sound } from './sound'

/** How long the note about being staked stays up. */
const STAKED_SHOWN_MS = 6_000

/**
 * What a table has said about the player's chips: that the house has staked
 * them, or that it will not deal until they have some. The same at every game.
 */
export function useChipNotices() {
  const [broke, setBroke] = useState<Broke | null>(null)
  const [staked, setStaked] = useState<number | null>(null)

  useEffect(() => {
    if (staked === null) return
    const timer = setTimeout(() => setStaked(null), STAKED_SHOWN_MS)
    return () => clearTimeout(timer)
  }, [staked])

  const receive = useCallback((notice: ChipNotice) => {
    if (notice.type === 'staked') {
      setStaked(notice.amount)
      sound.chipStack()
    } else {
      setBroke({ dailyReady: notice.dailyReady, nextChipsAt: notice.nextChipsAt })
    }
  }, [])

  /** The table has dealt, or is being asked to again. */
  const dealt = useCallback(() => setBroke(null), [])

  return { broke, staked, receive, dealt }
}

/** The time left until [at], kept current while it is on screen. */
export function useCountdown(at: string | null): string | null {
  const [, tick] = useState(0)

  useEffect(() => {
    if (!at) return
    const timer = setInterval(() => tick((count) => count + 1), 20_000)
    return () => clearInterval(timer)
  }, [at])

  return at ? until(at) : null
}
