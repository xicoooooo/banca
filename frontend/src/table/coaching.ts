import { useState } from 'react'
import type { PokerAdvice, TableView, TraceEvent } from './types'

/** The coach's part in the decision in front of the player: not asked, working, or answered. */
export type PokerCoaching = { status: 'idle' | 'thinking' | 'ready'; steps: TraceEvent[]; advice: PokerAdvice | null }

const NO_COACHING: PokerCoaching = { status: 'idle', steps: [], advice: null }

/** The decision a view is waiting on. Advice is for one of these and no other. */
export function decisionIn(view: TableView | null): string | null {
  if (!view || !view.legal || view.result) return null
  const me = view.players.find((player) => player.seat === view.yourSeat)
  return `${view.handNumber}:${view.street}:${view.pot}:${me?.committed}`
}

/**
 * The coach's part in the decision in front of the player. Once the hand moves
 * on, what it said is put away.
 */
export function usePokerCoaching(view: TableView | null) {
  const [coach, setCoach] = useState<PokerCoaching>(NO_COACHING)

  const decision = decisionIn(view)
  const [coachedDecision, setCoachedDecision] = useState(decision)
  if (coachedDecision !== decision) {
    setCoachedDecision(decision)
    setCoach(NO_COACHING)
  }

  return {
    coach,
    /** Marks the coach as asked, unless it already has been. Returns whether there is a question to send. */
    begin: (): boolean => {
      if (coach.status !== 'idle' || decision === null) return false
      setCoach({ status: 'thinking', steps: [], advice: null })
      return true
    },
    heardStep: (step: TraceEvent) =>
      setCoach((current) => (current.status === 'thinking' ? { ...current, steps: [...current.steps, step] } : current)),
    heardAdvice: (advice: PokerAdvice) =>
      setCoach((current) => (current.status === 'idle' ? current : { ...current, status: 'ready', advice })),
    // A coach that could not answer is no longer thinking.
    gaveUp: () => setCoach((current) => (current.status === 'thinking' ? NO_COACHING : current)),
  }
}

/** The advice in a few words: "Raise to 120", "Check". */
export function playOf(advice: PokerAdvice): string {
  const amount = advice.amount?.toLocaleString('en-US')
  switch (advice.action) {
    case 'bet':
      return `Bet ${amount}`
    case 'raise':
      return `Raise to ${amount}`
    default:
      return advice.action[0].toUpperCase() + advice.action.slice(1)
  }
}
