import { useRef, useState } from 'react'
import type { ChatLine, Phrase } from '../casino/room'
import { useChipNotices } from '../casino/useChipNotices'
import { useSocket } from '../casino/useSocket'
import type { PokerRoomClientMessage, PokerRoomServerMessage, PokerRoomView, TraceEvent } from './types'
import type { Reasoning } from './useTable'

const NO_REASONING: Reasoning = { handNumber: 0, events: [], revealed: false }

/** How many lines of chat are kept to look back over. */
const CHAT_KEPT = 30

/**
 * A shared poker table as the server describes it. The table deals the hands
 * and keeps the time; what is kept here is when its current wait ends by this
 * device's clock, what Banca did to reach its decisions, and what has been said.
 */
export function usePokerRoom(tableId: string) {
  const [room, setRoom] = useState<PokerRoomView | null>(null)
  /** When the table's current wait ends, by this device's clock, and how long it was when first heard of. */
  const [endsAt, setEndsAt] = useState(0)
  const [waitMs, setWaitMs] = useState(0)
  const [reasoning, setReasoning] = useState<Reasoning>(NO_REASONING)
  const [error, setError] = useState<string | null>(null)
  const [full, setFull] = useState(false)
  const [refusals, setRefusals] = useState(0)
  const [chat, setChat] = useState<ChatLine[]>([])
  const [phrases, setPhrases] = useState<Phrase[]>([])
  const chips = useChipNotices()
  const lastWait = useRef('')

  const heard = (handNumber: number, event: TraceEvent) =>
    setReasoning((current) => ({
      handNumber,
      events: current.handNumber === handNumber ? [...current.events, event] : [event],
      revealed: false,
    }))

  const { connection, sittings, send } = useSocket<PokerRoomServerMessage, PokerRoomClientMessage>(`/ws/poker/tables/${tableId}`, (message) => {
    switch (message.type) {
      case 'staked':
      case 'broke':
        chips.receive(message)
        break
      case 'state': {
        const next = message.view
        setRoom(next)
        setEndsAt(Date.now() + next.msLeft)
        const table = next.table
        // A new hand starts with a clean slate.
        if (table) {
          setReasoning((current) => (current.handNumber === table.handNumber ? current : { handNumber: table.handNumber, events: [], revealed: false }))
        }
        // A new hand, street or player to act is a new wait, with a clock of its own.
        const wait = `${table?.handNumber}:${next.phase}:${table?.actorSeat}:${table?.street}:${table?.pot}`
        if (wait !== lastWait.current) {
          lastWait.current = wait
          setWaitMs(next.msLeft)
          setError(null)
          if (next.dealtIn && next.phase === 'playing') chips.dealt()
        }
        break
      }
      case 'trace':
        heard(message.handNumber, message.event)
        break
      case 'reveal':
        setReasoning({ handNumber: message.handNumber, events: message.events, revealed: true })
        break
      case 'chat_log':
        setChat(message.lines)
        setPhrases(message.phrases)
        break
      case 'chat':
        setChat((lines) => [...lines, message.line].slice(-CHAT_KEPT))
        break
      case 'error':
        if (message.code === 'full') setFull(true)
        setError(message.message)
        setRefusals((count) => count + 1)
        break
    }
  })

  return {
    room,
    endsAt,
    waitMs,
    reasoning,
    connection,
    error,
    full,
    // Sitting back down counts with refusals as a reason to give the controls back.
    refusals: refusals + sittings,
    send,
    chat,
    phrases,
    broke: chips.broke,
    staked: chips.staked,
    retry: chips.dealt,
  }
}
