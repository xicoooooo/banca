import { spokenOnly, useReactionStream } from '../casino/reactionStream'
import { useRef, useState } from 'react'
import { NO_TALK, heardLine, heardLog } from '../casino/chat'
import type { Phrase } from '../casino/room'
import { useChipNotices } from '../casino/useChipNotices'
import { useSocket } from '../casino/useSocket'
import { usePokerCoaching } from './coaching'
import { withFrame } from './handFrames'
import type { PokerRoomClientMessage, PokerRoomServerMessage, PokerRoomView, TableView, TraceEvent } from './types'
import type { Reasoning } from './useTable'

const NO_REASONING: Reasoning = { handNumber: 0, events: [], revealed: false }

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
  /** Every state of the hand on the table, to play it back from once it is over. */
  const [frames, setFrames] = useState<TableView[]>([])
  const [error, setError] = useState<string | null>(null)
  const [full, setFull] = useState(false)
  const [refusals, setRefusals] = useState(0)
  const [talk, setTalk] = useState(NO_TALK)
  const [phrases, setPhrases] = useState<Phrase[]>([])
  const [reactions, react] = useReactionStream()
  const chips = useChipNotices()
  const lastWait = useRef('')
  /** What Banca said about a hand once it was over, and which hand that was. */
  const [remark, setRemark] = useState<{ handNumber: number; text: string } | null>(null)
  const handNumber = useRef(0)
  // Only the player's own turn is a decision to be coached on.
  const coaching = usePokerCoaching(room?.yourTurn ? room.table : null)

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
        if (table) {
          handNumber.current = table.handNumber
          setFrames((kept) => withFrame(kept, table))
        }
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
        setTalk((current) => heardLog(current, spokenOnly(message.lines)))
        setPhrases(message.phrases)
        break
      case 'chat':
        // A reaction is shown on the table and not added to what was said.
        if (message.line.emote) react(message.line)
        else setTalk((current) => heardLine(current, message.line))
        // Banca speaks only once a hand is over, so what it says is about the hand on the table.
        if (message.line.banca) setRemark({ handNumber: handNumber.current, text: message.line.text })
        break
      case 'coach_trace':
        coaching.heardStep(message.event)
        break
      case 'advice':
        coaching.heardAdvice(message.advice)
        break
      case 'error':
        coaching.gaveUp()
        if (message.code === 'full') setFull(true)
        setError(message.message)
        setRefusals((count) => count + 1)
        break
    }
  })

  return {
    room,
    frames,
    endsAt,
    waitMs,
    reasoning,
    connection,
    error,
    full,
    // Sitting back down counts with refusals as a reason to give the controls back.
    refusals: refusals + sittings,
    send,
    chat: talk.lines,
    // Every line heard since sitting down, which goes on counting once the kept lines are full.
    chatCount: talk.count,
    reactions,
    said: room?.table?.result && remark?.handNumber === room.table.handNumber ? remark.text : null,
    phrases,
    broke: chips.broke,
    staked: chips.staked,
    retry: chips.dealt,
    coach: coaching.coach,
    askCoach: () => {
      if (coaching.begin()) send({ type: 'advise' })
    },
  }
}
