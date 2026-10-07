import { useDialog } from '../casino/useDialog'
import { givenUp, summaryOf, type Reviewed } from './grading'
import type { BlackjackAction, DecisionReview, RoundReview } from './types'

const PLAYED: Record<BlackjackAction, string> = {
  hit: 'You hit',
  stand: 'You stood',
  double: 'You doubled',
  split: 'You split',
  insure: 'You took insurance',
  decline_insurance: 'You declined insurance',
}

const BETTER: Record<BlackjackAction, string> = {
  hit: 'Hitting',
  stand: 'Standing',
  double: 'Doubling',
  split: 'Splitting',
  insure: 'Taking insurance',
  decline_insurance: 'Declining insurance',
}

const VERDICT: Record<DecisionReview['verdict'], string> = { best: 'Best play', slip: 'Slip', mistake: 'Mistake' }

/** A value per chip, said as chips won or lost for every hundred staked. */
function per100(value: number): string {
  const chips = Math.round(value * 100)
  return chips === 0 ? '0' : `${chips > 0 ? '+' : '−'}${Math.abs(chips)}`
}

function situationOf(decision: DecisionReview): string {
  if (decision.played === 'insure' || decision.played === 'decline_insurance') return 'Insurance offered'
  const rank = decision.dealer[0]
  const dealer = rank === 'A' ? 'an ace' : rank === 'T' || rank === 'J' || rank === 'Q' || rank === 'K' ? 'a ten' : rank === '8' ? 'an 8' : `a ${rank}`
  return `${decision.soft ? 'Soft ' : ''}${decision.total} against ${dealer}`
}

/** One line under the result, saying how the round was played. Pressing it opens the full review. */
export function ReviewNote({ review, onOpen }: { review: RoundReview; onOpen: () => void }) {
  const clean = review.sound === review.decisions.length
  return (
    <button type="button" className="review-note rise-in" data-clean={clean} onClick={onOpen} aria-label={`Banca's review: ${summaryOf(review)}. Open it.`}>
      <span className="review-note__mark" aria-hidden>
        {clean ? '✓' : '!'}
      </span>
      <span>{summaryOf(review)}</span>
      <span className="review-note__more">Review</span>
    </button>
  )
}

/**
 * Every decision of a round set beside the best play. The grades are for the
 * decisions, not for how the cards fell: a good play can lose and a poor one win.
 */
export function ReviewPanel({ reviewed, onClose }: { reviewed: Reviewed; onClose: () => void }) {
  const closeButton = useDialog(onClose)
  const { review, roundNumber } = reviewed
  const several = new Set(review.decisions.map((decision) => decision.hand)).size > 1

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label="Banca's review of the round" onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">Review · Round {roundNumber}</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">
              {review.sound === review.decisions.length
                ? 'Played by the book'
                : `${review.sound} of ${review.decisions.length} by the book`}
            </h2>
            {review.cost > 0 && <p className="pt-1 text-sm text-ivory/75">About {givenUp(review.cost)} chips given up on average.</p>}
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="overflow-y-auto px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          <ol className="m-0 flex list-none flex-col gap-2.5 p-0">
            {review.decisions.map((decision, index) => (
              <li key={index} className="graded" data-verdict={decision.verdict}>
                <div className="flex items-baseline justify-between gap-3">
                  <p className="label">
                    {several && `Hand ${decision.hand + 1} · `}
                    {situationOf(decision)}
                  </p>
                  <p className="graded__verdict">
                    {VERDICT[decision.verdict]}
                    {decision.cost > 0 && <span className="figure"> · −{givenUp(decision.cost)}</span>}
                  </p>
                </div>
                <p className="pt-1 text-sm font-semibold text-ivory">{PLAYED[decision.played]}</p>

                {decision.verdict !== 'best' && (
                  <>
                    <p className="pt-1.5 text-sm leading-relaxed text-ivory/85">
                      {BETTER[decision.best]} was better. {decision.reason}
                    </p>
                    <p className="figure pt-1.5 text-xs text-muted">
                      {BETTER[decision.played]} {per100(decision.playedValue)} · {BETTER[decision.best]} {per100(decision.bestValue)} per 100 staked
                    </p>
                  </>
                )}
                {decision.coach && (
                  <p className="pt-1.5 text-xs text-muted">
                    {decision.coach === 'followed' ? 'You asked Banca and took its advice.' : 'You asked Banca and played it your own way.'}
                  </p>
                )}
              </li>
            ))}
          </ol>

          <p className="pt-4 text-xs leading-relaxed text-muted">
            Each decision is set against what every play returns on average, worked out from the rules of this table. It grades the choice, not the
            cards: a good play can lose and a poor one can win.
          </p>
        </div>
      </section>
    </div>
  )
}
