import type { CSSProperties } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { Card, CardSlot } from '../casino/Card'
import { ChipStack } from '../casino/Chip'
import { useCountBefore } from '../casino/useCountBefore'
import type { BlackjackHandView, BlackjackView } from './types'
import { TIMING, type Reveal } from './useBlackjack'

// The dealer, a hand and the result, as they are drawn at any blackjack table,
// played alone or with others.

const OUTCOME_LABEL = { blackjack: 'Blackjack', win: 'Win', push: 'Push', lose: 'Lose', bust: 'Bust' }

export function Dealer({ view, reveal, resultShown }: { view: BlackjackView; reveal: Reveal; resultShown: boolean }) {
  const dealer = view.dealer
  const settled = view.phase === 'settled'
  const bust = settled && resultShown && (dealer?.total ?? 0) > 21

  return (
    <section aria-label="Dealer" className="flex flex-col items-center gap-2">
      <p data-anchor="house" className="label">
        Dealer
      </p>

      <div className="fan min-h-[calc(var(--w)*1.4)]" style={{ '--w': 'var(--card-board)' } as CSSProperties}>
        {!dealer && [0, 1].map((index) => <CardSlot key={index} />)}
        {dealer?.cards.map((card, index) => {
          // Two cards in the opening deal, the second face down. Anything
          // more is drawn after the hole card has been turned.
          const dealAt =
            index < 2 ? (index * 2 + 1) * TIMING.DEAL_STAGGER : TIMING.DRAW_START + (index - 2) * TIMING.DRAW_STAGGER
          const flipAt =
            index === 0
              ? dealAt + 420
              : index === 1
                ? reveal.instant
                  ? TIMING.INSTANT_REVEAL
                  : TIMING.HOLE_FLIP
                : dealAt + 340

          return (
            <Card
              key={`${view.roundNumber}-${index}`}
              card={card}
              dealDelay={dealAt}
              dealFrom={{ x: '34vw', y: '-12vh' }}
              flipDelay={flipAt}
              seed={view.roundNumber * 20 + index}
            />
          )
        })}
      </div>

      <p className="h-6">
        {dealer && (
          // A total is only shown once the cards it counts have been turned,
          // and starts afresh each round rather than counting on from the last.
          <span
            key={view.roundNumber}
            className="total figure rise-in"
            data-tone={bust ? 'bad' : undefined}
            style={{ '--rise-delay': `${TIMING.TOTALS_AT}ms` } as CSSProperties}
          >
            <AnimatedNumber value={dealer.total} delay={settled ? TIMING.HOLE_FLIP + 250 : 0} duration={350} />
          </span>
        )}
      </p>
    </section>
  )
}

export function Hand({
  hand,
  index,
  view,
  resultShown,
  size,
}: {
  hand: BlackjackHandView
  index: number
  view: BlackjackView
  resultShown: boolean
  size: string
}) {
  const before = useCountBefore(hand.cards.length, view.roundNumber)
  const outcome = resultShown ? hand.outcome : null
  const waiting = view.phase === 'player' && view.activeHand !== index && hand.status === 'waiting'

  return (
    <div
      className="bj-hand flex flex-col items-center gap-1.5"
      data-waiting={waiting}
      data-outcome={outcome ?? undefined}
    >
      <div className="fan" style={{ '--w': size } as CSSProperties}>
        {hand.cards.map((card, cardIndex) => {
          // The opening two are dealt in turn with the dealer's; a card taken
          // later comes straight away.
          const dealAt = before === 0 ? cardIndex * 2 * TIMING.DEAL_STAGGER : 0
          return (
            <Card
              key={`${view.roundNumber}-${cardIndex}-${card}`}
              card={card}
              dealDelay={dealAt}
              dealFrom={{ x: '30vw', y: '-34vh' }}
              flipDelay={dealAt + 400}
              liftable
              seed={view.roundNumber * 20 + 5 + index * 5 + cardIndex}
            />
          )
        })}
      </div>

      <p className="flex h-6 items-center gap-2">
        <span
          key={view.roundNumber}
          className="total figure rise-in"
          data-tone={hand.status === 'bust' ? 'bad' : hand.total === 21 ? 'good' : undefined}
          // The opening hand's total waits for its cards to be turned. A hand
          // made by splitting is already in view, so its total is not held back.
          style={{ '--rise-delay': `${before === 0 && view.hands.length === 1 ? TIMING.TOTALS_AT : 0}ms` } as CSSProperties}
        >
          {hand.soft && hand.total < 21 ? `${hand.total - 10}/${hand.total}` : hand.total}
        </span>
        {outcome ? (
          <span className={`label rise-in ${outcome === 'win' || outcome === 'blackjack' ? 'text-gold-bright!' : ''}`}>
            {OUTCOME_LABEL[outcome]}
          </span>
        ) : (
          hand.status === 'doubled' && <span className="label text-gold!">Doubled</span>
        )}
      </p>

      <p className="bet-row flex h-6 items-center gap-2">
        <span data-anchor={index === 0 ? 'bj-bet' : undefined} className="grid h-6 w-6 place-items-center">
          {!resultShown && <ChipStack amount={hand.bet} bigBlind={view.minBet * 2} size={20} />}
        </span>
        {!resultShown && <span className="figure text-sm font-semibold text-ivory">{hand.bet.toLocaleString('en-US')}</span>}
      </p>
    </div>
  )
}

/** The result, said on the felt where the house rules are printed. */
export function Result({ view }: { view: BlackjackView }) {
  const result = view.result!
  const natural = view.hands.some((hand) => hand.outcome === 'blackjack')
  const allBust = view.hands.every((hand) => hand.outcome === 'bust')
  const headline =
    result.net > 0 ? (natural ? 'Blackjack' : 'You win') : result.net === 0 ? 'Push' : allBust ? 'Bust' : 'Dealer wins'

  const note = result.refilled
    ? 'Out of chips, so the house has staked you'
    : view.hands.length > 1
      ? view.hands.map((hand) => OUTCOME_LABEL[hand.outcome!]).join(' · ')
      : null

  return (
    <div aria-live="polite" className="rise-in text-center">
      <p className={`text-2xl leading-tight font-semibold tracking-tight ${result.net > 0 ? 'text-gold-bright' : 'text-ivory'}`}>
        {headline}
        {result.net !== 0 && (
          <span className="figure">
            {' '}
            {result.net > 0 ? '+' : '−'}
            {Math.abs(result.net).toLocaleString('en-US')}
          </span>
        )}
      </p>
      {note && <p className="label pt-1">{note}</p>}
    </div>
  )
}
