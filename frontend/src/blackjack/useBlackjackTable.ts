import { useMemo, useRef, useState } from 'react'
import type { ChatLine, Phrase } from '../casino/room'
import { useChipNotices } from '../casino/useChipNotices'
import { useSocket } from '../casino/useSocket'
import type { BlackjackTableClientMessage, BlackjackTableServerMessage, BlackjackTableView, BlackjackView } from './types'
import { useBlackjackPresentation, useCoaching } from './useBlackjack'

/** How many lines of chat are kept to look back over. */
const CHAT_KEPT = 30

/**
 * The player's own part in a shared round, put the way a table played alone
 * would put it, so the same cards and the same timing can draw both.
 *
 * Alone, a round is settled when the dealer has played. At a shared table a
 * player can be finished long before that, with others still to act, and the
 * dealer's cards are not turned until the table's round is over. So here the
 * round only reads as settled once the whole table's is.
 */
export function ownRoundAt(table: BlackjackTableView): BlackjackView {
  const you = table.you
  if (table.phase === 'results') return { ...you, phase: 'settled' }
  return you.phase === 'settled' ? { ...you, phase: 'waiting' } : you
}

/**
 * A shared blackjack table as the server describes it. The table keeps the
 * time and decides everything; what is kept here is when its current wait
 * ends by this device's clock, what has been said, and the coach.
 */
export function useBlackjackTable(tableId: string) {
  const [table, setTable] = useState<BlackjackTableView | null>(null)
  /** When the table's current wait ends, by this device's clock, and how long it was when first heard of. */
  const [endsAt, setEndsAt] = useState(0)
  const [waitMs, setWaitMs] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [full, setFull] = useState(false)
  const [refusals, setRefusals] = useState(0)
  const [chat, setChat] = useState<ChatLine[]>([])
  const [phrases, setPhrases] = useState<Phrase[]>([])
  const chips = useChipNotices()
  const lastWait = useRef('')

  const view = useMemo(() => (table ? ownRoundAt(table) : null), [table])
  const coaching = useCoaching(view)

  const { connection, sittings, send } = useSocket<BlackjackTableServerMessage, BlackjackTableClientMessage>(
    `/ws/blackjack/tables/${tableId}`,
    (message) => {
      switch (message.type) {
        case 'staked':
        case 'broke':
          chips.receive(message)
          break
        case 'state': {
          const next = message.view
          setTable(next)
          setEndsAt(Date.now() + next.msLeft)
          // A new phase, or a new player's turn, is a new wait with a clock of its own.
          const wait = `${next.roundNumber}:${next.phase}:${next.actor}:${next.you.activeHand}:${next.you.hands.map((hand) => hand.cards.length).join('.')}`
          if (wait !== lastWait.current) {
            lastWait.current = wait
            setWaitMs(next.msLeft)
            setError(null)
            if (next.phase === 'betting') chips.dealt()
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
          coaching.heardStep(message.event)
          break
        case 'advice':
          coaching.heardAdvice(message.advice)
          break
        case 'error':
          if (message.code === 'full') setFull(true)
          setError(message.message)
          setRefusals((count) => count + 1)
          coaching.gaveUp()
          break
      }
    },
  )

  const reveal = useBlackjackPresentation(view)

  return {
    table,
    view,
    reveal,
    endsAt,
    waitMs,
    connection,
    error,
    full,
    // Sitting back down counts with refusals as a reason to give the controls back.
    refusals: refusals + sittings,
    send,
    chat,
    phrases,
    coach: coaching.coach,
    askCoach: () => {
      if (coaching.begin()) send({ type: 'advise' })
    },
    broke: chips.broke,
    staked: chips.staked,
    retry: chips.dealt,
  }
}
