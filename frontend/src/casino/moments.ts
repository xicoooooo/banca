import type { RouletteResult } from '../roulette/types'
import type { TableView } from '../table/types'

// The rare things a table stops to celebrate: a hand hardly ever seen, the
// number that came in, a run of wins. The server finds the same things in the
// player's record afterwards, for their profile; these are the rules as the
// table applies them while it is happening.

/** Something to celebrate. `key` is its own, so the same moment is never celebrated twice. */
export type Moment = { key: string; title: string; detail: string }

/** The Hold'em hands rare enough to stop the table for, by the name the server gives them. */
const RARE_HANDS: Record<string, string> = {
  four_of_a_kind: 'Four of a kind',
  straight_flush: 'Straight flush',
  royal_flush: 'Royal flush',
}

/** Blackjack rounds won one after another before it counts as a run. */
export const RUN = 5

const chips = (amount: number) => amount.toLocaleString('en-US')

/** The moment in a finished hand of Hold'em: the player won it at a showdown with a rare hand. */
export function pokerMoment(view: TableView | null): Moment | null {
  const result = view?.result
  if (!view || !result) return null
  const title = RARE_HANDS[result.showdown[view.yourSeat] ?? '']
  const won = result.winnings[view.yourSeat] ?? 0
  if (!title || won <= 0) return null
  return { key: `poker-${view.handNumber}`, title, detail: `You took ${chips(won)}` }
}

/** A spin is a big one when it wins this many times everything that was staked on it. */
export const BIG_SPIN = 10

/**
 * The moment in a spin: one of the player's single numbers came in, and the
 * spin won many times what was staked on it. A number is 35 to 1, so that is
 * a player who backed one, two or three of them and was right. Someone who
 * covers half the wheel sees a number come in most spins, for little, and
 * that is nothing to stop the table for.
 */
export function rouletteMoment(result: RouletteResult | null, roundNumber: number): Moment | null {
  const hit = result?.wagers.find((wager) => wager.kind === 'straight' && wager.returned > 0)
  if (!result || !hit || result.net < BIG_SPIN * result.staked) return null
  return {
    key: `roulette-${roundNumber}`,
    title: 'Straight up',
    detail: `${result.pocket} came in and paid ${chips(hit.returned)}`,
  }
}

/** How long a run of wins is after a round that came to [net]: one longer for a win, and over for anything else. */
export const runAfter = (run: number, net: number) => (net > 0 ? run + 1 : 0)

/** The moment in a run of blackjack wins, which is the round that brings it to five. */
export function runMoment(run: number, roundNumber: number): Moment | null {
  return run === RUN ? { key: `blackjack-${roundNumber}`, title: 'Five in a row', detail: 'Five rounds won one after another' } : null
}
