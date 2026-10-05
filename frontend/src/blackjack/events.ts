import type { BlackjackView, Outcome } from './types'

/**
 * What happened at the blackjack table between two views. As with poker, the
 * server sends whole states and the movement is worked out here by comparing
 * them, in a pure function.
 */
export type BlackjackEvent =
  | { type: 'round_started'; bet: number }
  /** More chips went onto a hand: a double, or the second stake of a split. */
  | { type: 'stake_added'; amount: number }
  | { type: 'player_card' }
  | { type: 'insurance'; amount: number }
  /** The dealer turned the hole card and drew [dealerDrew] more; each hand has its outcome. */
  | { type: 'settled'; dealerDrew: number; hands: { outcome: Outcome; bet: number; returned: number }[]; net: number }

const staked = (view: BlackjackView) => view.hands.reduce((sum, hand) => sum + hand.bet, 0)
const held = (view: BlackjackView) => view.hands.reduce((sum, hand) => sum + hand.cards.length, 0)

export function deriveBlackjackEvents(before: BlackjackView | null, now: BlackjackView): BlackjackEvent[] {
  if (before === now || now.roundNumber === 0) return []

  const events: BlackjackEvent[] = []
  const sameRound = before !== null && before.roundNumber === now.roundNumber

  if (!sameRound) {
    events.push({ type: 'round_started', bet: now.hands[0]?.bet ?? 0 })
  } else {
    const added = staked(now) - staked(before)
    if (added > 0) events.push({ type: 'stake_added', amount: added })

    // A split deals two cards at once, which reads as one moment at the table.
    if (held(now) > held(before)) events.push({ type: 'player_card' })

    // Insurance is the one way chips leave the stack without landing on a hand.
    if (before.phase === 'insurance' && added === 0 && now.stack < before.stack && now.phase !== 'settled') {
      events.push({ type: 'insurance', amount: before.stack - now.stack })
    }
  }

  const justSettled = now.phase === 'settled' && now.result !== null && !(sameRound && before.phase === 'settled')
  if (justSettled && now.dealer) {
    const shownBefore = sameRound && before.dealer ? before.dealer.cards.length : 2
    events.push({
      type: 'settled',
      dealerDrew: now.dealer.cards.length - shownBefore,
      hands: now.hands.map((hand) => ({ outcome: hand.outcome ?? 'lose', bet: hand.bet, returned: hand.returned ?? 0 })),
      net: now.result!.net,
    })
  }

  return events
}
