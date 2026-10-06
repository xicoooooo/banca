import { useCallback, useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from '../casino/motion'
import { sound } from '../casino/sound'
import { useChipNotices } from '../casino/useChipNotices'
import { useSocket } from '../casino/useSocket'
import type { LayoutRead, ReadStep, RouletteClientMessage, RouletteServerMessage, RouletteView, Wager } from './types'

/** Banca's part in the layout on the felt: not asked, working, or answered. */
export type Reading = { status: 'idle' | 'thinking' | 'ready'; steps: ReadStep[]; read: LayoutRead | null }

const NOT_READING: Reading = { status: 'idle', steps: [], read: null }

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
  const [reading, setReading] = useState<Reading>(NOT_READING)

  const { connection, send } = useSocket<RouletteServerMessage, RouletteClientMessage>('/ws/roulette', (message) => {
    if (message.type === 'staked' || message.type === 'broke') {
      chips.receive(message)
    } else if (message.type === 'trace') {
      setReading((current) => (current.status === 'thinking' ? { ...current, steps: [...current.steps, message.event] } : current))
    } else if (message.type === 'read') {
      setReading((current) => (current.status === 'idle' ? current : { ...current, status: 'ready', read: message.read }))
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
      // An analyst that could not answer is no longer thinking.
      setReading((current) => (current.status === 'thinking' ? NOT_READING : current))
    }
  })

  /** Asks Banca about a layout. The answer is for these bets only. */
  const askAbout = (bets: Wager[]) => {
    if (reading.status !== 'idle' || bets.length === 0) return
    setReading({ status: 'thinking', steps: [], read: null })
    send({ type: 'analyse', bets })
  }

  /** Puts the read away, when the layout it was about has changed. */
  const forgetRead = useCallback(() => setReading(NOT_READING), [])

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

  return { view, spinning, connection, error, refusals, send, broke: chips.broke, staked: chips.staked, retry: chips.dealt, reading, askAbout, forgetRead }
}
