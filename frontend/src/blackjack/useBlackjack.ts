import { useEffect, useRef, useState } from 'react'
import { useChipNotices } from '../casino/useChipNotices'
import { chipsFor } from '../casino/chips'
import { flyChips } from '../casino/flights'
import { sound } from '../casino/sound'
import { useSocket } from '../casino/useSocket'
import { deriveBlackjackEvents } from './events'
import type { Advice, BlackjackClientMessage, BlackjackServerMessage, BlackjackView, CoachStep } from './types'

/**
 * When things happen, in milliseconds, so cards, chips, sounds and labels all
 * keep the same time.
 */
export const TIMING = {
  /** Between one card and the next in the opening deal. */
  DEAL_STAGGER: 150,
  /** After the round settles, when the dealer turns the hole card. */
  HOLE_FLIP: 350,
  /** When the dealer's first drawn card leaves the shoe, and the gap to each one after. */
  DRAW_START: 950,
  DRAW_STAGGER: 520,
  /** A round settled by the deal itself still has to be dealt before it is paid. */
  INSTANT_REVEAL: 1250,
  /** When the opening cards have been dealt and turned, so their totals may be shown. */
  TOTALS_AT: 1150,
}

/** How long after settling the result should be shown: once the dealer has finished. */
export function resultDelay(dealerDrew: number, instant: boolean): number {
  if (instant) return TIMING.INSTANT_REVEAL + 650
  return (dealerDrew > 0 ? TIMING.DRAW_START + (dealerDrew - 1) * TIMING.DRAW_STAGGER : TIMING.HOLE_FLIP) + 750
}

/** The coach's part in the decision in front of the player: not asked, working, or answered. */
export type Coaching = { status: 'idle' | 'thinking' | 'ready'; steps: CoachStep[]; advice: Advice | null }

const NO_COACHING: Coaching = { status: 'idle', steps: [], advice: null }

/** What the player is being asked to decide, as something that changes whenever the decision does. */
function decisionIn(view: BlackjackView): string {
  const hand = view.hands[view.activeHand ?? 0]
  return `${view.roundNumber}:${view.phase}:${view.activeHand}:${hand?.cards.join('') ?? ''}`
}

export type Reveal = {
  roundNumber: number
  /** Milliseconds from settling until the outcome may be shown. */
  delay: number
  /** True when the round was over as soon as it was dealt. */
  instant: boolean
}

/** The blackjack table as the server describes it, with the movement and sound that go with each change. */
export function useBlackjack() {
  const [view, setView] = useState<BlackjackView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refusals, setRefusals] = useState(0)
  const [reveal, setReveal] = useState<Reveal>({ roundNumber: 0, delay: 0, instant: false })
  const previous = useRef<BlackjackView | null>(null)
  const chips = useChipNotices()
  const [coach, setCoach] = useState<Coaching>(NO_COACHING)

  const { connection, send } = useSocket<BlackjackServerMessage, BlackjackClientMessage>('/ws/blackjack', (message) => {
    if (message.type === 'staked' || message.type === 'broke') {
      chips.receive(message)
    } else if (message.type === 'trace') {
      setCoach((current) => (current.status === 'thinking' ? { ...current, steps: [...current.steps, message.event] } : current))
    } else if (message.type === 'advice') {
      setCoach((current) => (current.status === 'idle' ? current : { ...current, status: 'ready', advice: message.advice }))
    } else if (message.type === 'state') {
      // The table says first where things stand, and only then that it cannot deal.
      chips.dealt()
      setView(message.view)
      setError(null)
    } else {
      setError(message.message)
      setRefusals((count) => count + 1)
      // A coach that could not answer is no longer thinking.
      setCoach((current) => (current.status === 'thinking' ? NO_COACHING : current))
    }
  })

  // Advice is for one decision. Once the cards or the question change, it is put away.
  const decision = view ? decisionIn(view) : null
  const [coachedDecision, setCoachedDecision] = useState(decision)
  if (coachedDecision !== decision) {
    setCoachedDecision(decision)
    setCoach(NO_COACHING)
  }

  const askCoach = () => {
    if (coach.status !== 'idle') return
    setCoach({ status: 'thinking', steps: [], advice: null })
    send({ type: 'advise' })
  }

  useEffect(() => {
    if (!view || previous.current === view) return
    const events = deriveBlackjackEvents(previous.current, view)
    previous.current = view

    const chips = (amount: number) => chipsFor(amount, view.minBet * 2)
    const instant = events.length > 1 && events[0].type === 'round_started' && events.some((e) => e.type === 'settled')

    for (const event of events) {
      switch (event.type) {
        case 'round_started':
          flyChips('stack-0', 'bj-bet', chips(event.bet))
          sound.chipClink()
          for (let card = 0; card < 4; card++) sound.cardDeal(card * TIMING.DEAL_STAGGER)
          break

        case 'stake_added':
          flyChips('stack-0', 'bj-bet', chips(event.amount))
          sound.chipClink()
          break

        case 'player_card':
          sound.cardDeal()
          sound.cardFlip(340)
          break

        case 'insurance':
          flyChips('stack-0', 'house', chips(event.amount))
          sound.chipClink()
          break

        case 'settled': {
          const delay = resultDelay(event.dealerDrew, instant)
          setReveal({ roundNumber: view.roundNumber, delay, instant })

          sound.cardFlip(instant ? TIMING.INSTANT_REVEAL : TIMING.HOLE_FLIP)
          for (let card = 0; card < event.dealerDrew; card++) {
            sound.cardDeal(TIMING.DRAW_START + card * TIMING.DRAW_STAGGER)
          }

          // Chips move once the dealer has finished: to the house for a lost
          // hand, back to the player for anything else, with winnings on top.
          event.hands.forEach((hand, index) => {
            const at = delay + index * 140
            if (hand.returned === 0) {
              flyChips('bj-bet', 'house', chips(hand.bet), at, 'gather')
            } else {
              flyChips('bj-bet', 'stack-0', chips(hand.bet), at, 'gather')
              if (hand.returned > hand.bet) flyChips('house', 'stack-0', chips(hand.returned - hand.bet), at + 120, 'gather')
            }
          })
          sound.chipStack(delay)
          if (event.net > 0) sound.win(delay + 250)
          break
        }
      }
    }
  }, [view])

  return { view, reveal, connection, error, refusals, send, broke: chips.broke, staked: chips.staked, retry: chips.dealt, coach, askCoach }
}
