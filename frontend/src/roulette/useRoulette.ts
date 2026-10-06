import { useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from '../casino/motion'
import { sound } from '../casino/sound'
import { useChipNotices } from '../casino/useChipNotices'
import { useSocket } from '../casino/useSocket'
import type { RouletteClientMessage, RouletteServerMessage, RouletteView } from './types'

/** How long the wheel turns before the ball is seen to have landed. */
export const SPIN_MS = 4_400

/**
 * The roulette table as the server describes it. The server answers a spin at
 * once, with the result; what is kept here is the gap between knowing it and
 * showing it, so the wheel has time to turn.
 */
export function useRoulette() {
  const [view, setView] = useState<RouletteView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refusals, setRefusals] = useState(0)
  /** The last round whose result has been shown. A later round in the view is still spinning. */
  const [shownRound, setShownRound] = useState(0)
  const first = useRef(true)
  const chips = useChipNotices()

  const { connection, send } = useSocket<RouletteServerMessage, RouletteClientMessage>('/ws/roulette', (message) => {
    if (message.type === 'staked' || message.type === 'broke') {
      chips.receive(message)
    } else if (message.type === 'state') {
      chips.dealt()
      setView(message.view)
      setError(null)
      // Sitting back down at a table that has already spun shows no old result.
      if (first.current) setShownRound(message.view.roundNumber)
      first.current = false
    } else {
      setError(message.message)
      setRefusals((count) => count + 1)
    }
  })

  const roundNumber = view?.roundNumber ?? 0
  const spinning = roundNumber > shownRound

  useEffect(() => {
    if (!spinning || !view?.result) return
    const length = prefersReducedMotion() ? 0 : SPIN_MS

    // The ball clicks over the pockets, quickly at first and then slower.
    const ticks: ReturnType<typeof setTimeout>[] = []
    for (let at = 120, gap = 70; at < length - 300; at += gap, gap *= 1.16) ticks.push(setTimeout(() => sound.click(), at))

    const net = view.result.net
    const reveal = setTimeout(() => {
      setShownRound(roundNumber)
      if (net > 0) {
        sound.chipStack()
        sound.win(200)
      }
    }, length)

    return () => {
      ticks.forEach(clearTimeout)
      clearTimeout(reveal)
    }
  }, [spinning, roundNumber, view?.result])

  return { view, spinning, connection, error, refusals, send, broke: chips.broke, staked: chips.staked, retry: chips.dealt }
}
