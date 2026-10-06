import { useCallback, useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from '../casino/motion'
import { sound } from '../casino/sound'
import { useChipNotices } from '../casino/useChipNotices'
import { useSocket } from '../casino/useSocket'
import type { ChatLine, Phrase, RoomClientMessage, RoomServerMessage, RoomView, Wager } from './types'
import type { Reading } from './useRoulette'

const NOT_READING: Reading = { status: 'idle', steps: [], read: null }

/** How many lines of chat are kept to look back over. */
const CHAT_KEPT = 30

/**
 * A shared roulette room as the server describes it. The room keeps the time
 * and decides everything; what is kept here is when its current phase ends by
 * this device's clock, what has been said, and Banca's read of the player's bets.
 */
export function useRoom(roomId: string) {
  const [view, setView] = useState<RoomView | null>(null)
  /** When the room's current phase ends, by this device's clock. */
  const [endsAt, setEndsAt] = useState(0)
  /** How long the current phase had left when this device first heard of it, for a clock that runs down. */
  const [phaseMs, setPhaseMs] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [refusals, setRefusals] = useState(0)
  const [chat, setChat] = useState<ChatLine[]>([])
  const [phrases, setPhrases] = useState<Phrase[]>([])
  const [reading, setReading] = useState<Reading>(NOT_READING)
  const chips = useChipNotices()
  const lastPhase = useRef('')

  const { connection, sittings, send } = useSocket<RoomServerMessage, RoomClientMessage>(`/ws/roulette/rooms/${roomId}`, (message) => {
    switch (message.type) {
      case 'staked':
      case 'broke':
        chips.receive(message)
        break
      case 'state': {
        const next = message.view
        setView(next)
        setEndsAt(Date.now() + next.msLeft)
        const phase = `${next.roundNumber}:${next.phase}`
        if (phase !== lastPhase.current) {
          lastPhase.current = phase
          setPhaseMs(next.msLeft)
          // A refusal belongs to the moment it was given.
          setError(null)
          if (next.phase === 'betting') chips.dealt()
          if (next.phase === 'results' && (next.result?.net ?? 0) > 0) {
            sound.chipStack()
            sound.win(200)
          }
        }
        break
      }
      case 'chat_log':
        setChat(message.lines)
        setPhrases(message.phrases)
        break
      case 'chat':
        setChat((lines) => [...lines, message.line].slice(-CHAT_KEPT))
        break
      case 'trace':
        setReading((current) => (current.status === 'thinking' ? { ...current, steps: [...current.steps, message.event] } : current))
        break
      case 'read':
        setReading((current) => (current.status === 'idle' ? current : { ...current, status: 'ready', read: message.read }))
        break
      case 'error':
        setError(message.message)
        setRefusals((count) => count + 1)
        setReading((current) => (current.status === 'thinking' ? NOT_READING : current))
        break
    }
  })

  // The ball clicks over the pockets while the wheel turns.
  const spinningRound = view?.phase === 'spinning' ? view.roundNumber : 0
  useEffect(() => {
    if (!spinningRound || prefersReducedMotion()) return
    const ticks: ReturnType<typeof setTimeout>[] = []
    for (let at = 120, gap = 70; at < 4_000; at += gap, gap *= 1.16) ticks.push(setTimeout(() => sound.click(), at))
    return () => ticks.forEach(clearTimeout)
  }, [spinningRound])

  const askAbout = (bets: Wager[]) => {
    if (reading.status !== 'idle' || bets.length === 0) return
    setReading({ status: 'thinking', steps: [], read: null })
    send({ type: 'analyse', bets })
  }

  const forgetRead = useCallback(() => setReading(NOT_READING), [])

  return {
    view,
    endsAt,
    phaseMs,
    connection,
    error,
    // Sitting back down counts with refusals as a reason to show the room's bets rather than this device's.
    refusals: refusals + sittings,
    send,
    chat,
    phrases,
    reading,
    askAbout,
    forgetRead,
    broke: chips.broke,
    staked: chips.staked,
    retry: chips.dealt,
  }
}

/** Whole seconds until [endsAt], kept current while it is on screen. */
export function useSecondsUntil(endsAt: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(timer)
  }, [endsAt])
  return Math.max(0, Math.ceil((endsAt - now) / 1000))
}
