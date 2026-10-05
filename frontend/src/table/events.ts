import type { TableView } from './types'

/**
 * What happened at the table between two views, worked out by comparing them.
 *
 * The server sends whole states, not events, and that stays as it is. Animation
 * needs to know what changed, so that is derived here, on the presentation side,
 * as a pure function: the same two views always give the same events.
 */
export type TableEvent =
  | { type: 'hand_started' }
  | { type: 'blind'; seat: number; amount: number }
  | { type: 'bet'; seat: number; amount: number; label: string }
  | { type: 'check'; seat: number }
  | { type: 'fold'; seat: number }
  /** Bets in front of these seats are swept into the pot. */
  | { type: 'collect'; seats: number[] }
  | { type: 'board'; cards: number }
  | { type: 'showdown' }
  | { type: 'won'; seats: number[]; amounts: Record<number, number> }

export function deriveEvents(before: TableView | null, now: TableView): TableEvent[] {
  // The same view again means nothing happened. Without this it would read as
  // the player to act having checked.
  if (before === now) return []

  if (!before || before.handNumber !== now.handNumber) {
    return [
      { type: 'hand_started' },
      ...now.players
        .filter((player) => player.committed > 0)
        .map((player) => ({ type: 'blind' as const, seat: player.seat, amount: player.committed })),
    ]
  }

  const events: TableEvent[] = []
  const actor = before.actorSeat
  const handEnded = now.result !== null && before.result === null
  const boardGrew = now.board.length > before.board.length

  // Chips this action put in. The pot counts everything paid into the hand, so
  // it is reliable even when the street ends and bets in front are cleared.
  const paid = now.pot - before.pot
  const inFront = new Map(before.players.map((player) => [player.seat, player.committed]))

  if (actor !== null) {
    const was = before.players.find((player) => player.seat === actor)
    const is = now.players.find((player) => player.seat === actor)

    if (was && is) {
      if (is.status === 'folded' && was.status !== 'folded') {
        events.push({ type: 'fold', seat: actor })
      } else if (paid > 0) {
        const highest = Math.max(...before.players.map((player) => player.committed))
        const owed = highest - was.committed
        const total = was.committed + paid
        const allIn = is.status === 'all_in'

        const label = allIn
          ? `All-in ${total}`
          : owed > 0
            ? paid <= owed
              ? `Call ${paid}`
              : `Raise to ${total}`
            : `Bet ${total}`

        events.push({ type: 'bet', seat: actor, amount: paid, label })
        inFront.set(actor, total)
      } else {
        events.push({ type: 'check', seat: actor })
      }
    }
  }

  if (boardGrew || handEnded) {
    const seats = [...inFront].filter(([, chips]) => chips > 0).map(([seat]) => seat)
    if (seats.length > 0) events.push({ type: 'collect', seats })
  }

  if (boardGrew) {
    events.push({ type: 'board', cards: now.board.length - before.board.length })
  }

  if (handEnded && now.result) {
    if (Object.keys(now.result.showdown).length > 0) events.push({ type: 'showdown' })

    const amounts: Record<number, number> = {}
    for (const [seat, chips] of Object.entries(now.result.winnings)) amounts[Number(seat)] = chips
    events.push({ type: 'won', seats: Object.keys(amounts).map(Number), amounts })
  }

  return events
}
