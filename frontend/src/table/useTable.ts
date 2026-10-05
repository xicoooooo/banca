import { useState } from 'react'
import { useSocket } from '../casino/useSocket'
import type { ClientMessage, ServerMessage, TableView, TraceEvent } from './types'

/** What the opponent did to reach its decisions in one hand. */
export type Reasoning = {
  handNumber: number
  events: TraceEvent[]
  /** True once the server has sent the private detail, after the hand. */
  revealed: boolean
}

const NO_REASONING: Reasoning = { handNumber: 0, events: [], revealed: false }

/** The poker table as the server describes it, kept up to date. */
export function useTable() {
  const [view, setView] = useState<TableView | null>(null)
  const [reasoning, setReasoning] = useState<Reasoning>(NO_REASONING)
  const [error, setError] = useState<string | null>(null)
  // Counts refusals, so the same error twice in a row is still seen as news.
  const [refusals, setRefusals] = useState(0)

  const { connection, send } = useSocket<ServerMessage, ClientMessage>('/ws/table', (message) => {
    switch (message.type) {
      case 'state':
        setView(message.view)
        setError(null)
        // A new hand starts with a clean slate.
        setReasoning((current) =>
          current.handNumber === message.view.handNumber
            ? current
            : { handNumber: message.view.handNumber, events: [], revealed: false },
        )
        break
      case 'trace':
        setReasoning((current) => ({
          handNumber: message.handNumber,
          events: current.handNumber === message.handNumber ? [...current.events, message.event] : [message.event],
          revealed: false,
        }))
        break
      case 'reveal':
        setReasoning({ handNumber: message.handNumber, events: message.events, revealed: true })
        break
      case 'error':
        setError(message.message)
        setRefusals((count) => count + 1)
        break
    }
  })

  return { view, reasoning, connection, error, refusals, send }
}
