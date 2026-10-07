import { useState } from 'react'
import type { BlackjackView, RoundReview } from './types'

/** Chips given up, to one decimal only when that is all there is. */
export function givenUp(cost: number): string {
  return cost >= 10 ? Math.round(cost).toLocaleString('en-US') : cost.toLocaleString('en-US', { maximumFractionDigits: 1 })
}

/** The round in a few words: how many decisions were right, and what the others came to. */
export function summaryOf(review: RoundReview): string {
  const missed = review.decisions.length - review.sound
  if (missed === 0) return 'Played by the book'
  const mistakes = review.decisions.filter((decision) => decision.verdict === 'mistake').length
  const slips = missed - mistakes
  const what = [mistakes > 0 && (mistakes === 1 ? '1 mistake' : `${mistakes} mistakes`), slips > 0 && (slips === 1 ? '1 slip' : `${slips} slips`)]
    .filter(Boolean)
    .join(', ')
  return review.cost > 0 ? `${what} · ${givenUp(review.cost)} chips given up` : what
}

export type Reviewed = { review: RoundReview; roundNumber: number }

/**
 * The last round's review, kept after the table has moved on. A shared table
 * leaves a result up for only a few seconds, which is not long enough to read
 * what went wrong, so the review stays to hand until the next one replaces it.
 */
export function useLastReview(view: BlackjackView | null): Reviewed | null {
  const [last, setLast] = useState<Reviewed | null>(null)
  const review = view?.review ?? null
  // Set while drawing: a new review is taken up in the same pass that brought it.
  if (view && review && last?.review !== review && last?.roundNumber !== view.roundNumber) {
    setLast({ review, roundNumber: view.roundNumber })
  }
  // A round with nothing to grade has no review, and the one before it is stale.
  if (view && !review && view.phase === 'settled' && last && last.roundNumber !== view.roundNumber) setLast(null)
  return last
}
