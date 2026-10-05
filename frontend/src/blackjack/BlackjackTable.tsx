import { useEffect, useState, type CSSProperties } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { Card, CardSlot } from '../casino/Card'
import { CasinoShell } from '../casino/CasinoShell'
import { ChipStack } from '../casino/Chip'
import { Header, type Status } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { prefersReducedMotion } from '../casino/motion'
import { Plate } from '../casino/Plate'
import { useCountBefore } from '../casino/useCountBefore'
import { BetControls } from './BetControls'
import { PlayControls } from './PlayControls'
import type { BlackjackHandView, BlackjackView } from './types'
import { TIMING, useBlackjack, type Reveal } from './useBlackjack'

const OUTCOME_LABEL = { blackjack: 'Blackjack', win: 'Win', push: 'Push', lose: 'Lose', bust: 'Bust' }

/**
 * True once the dealer has finished and the result may be said out loud. The
 * server settles a round in one step; the table takes a moment to play it out.
 */
function useResultShown(view: BlackjackView | null, reveal: Reveal): boolean {
  const [shownRound, setShownRound] = useState(0)
  const settled = view?.phase === 'settled'
  const round = view?.roundNumber ?? 0

  useEffect(() => {
    if (!settled || reveal.roundNumber !== round) return
    const timer = setTimeout(() => setShownRound(round), prefersReducedMotion() ? 0 : reveal.delay)
    return () => clearTimeout(timer)
  }, [settled, round, reveal])

  return settled && shownRound === round
}

function statusOf(view: BlackjackView, resultShown: boolean): Status {
  switch (view.phase) {
    case 'betting':
      return { text: 'Your bet', tone: 'gold' }
    case 'insurance':
      return { text: 'Insurance?', tone: 'gold' }
    case 'player':
      return { text: 'Your turn', tone: 'gold' }
    case 'settled':
      return resultShown ? { text: 'Your bet', tone: 'gold' } : { text: 'Dealer plays', tone: 'emerald' }
  }
}

function Dealer({ view, reveal, resultShown }: { view: BlackjackView; reveal: Reveal; resultShown: boolean }) {
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

function Hand({
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
function Result({ view }: { view: BlackjackView }) {
  const result = view.result!
  const natural = view.hands.some((hand) => hand.outcome === 'blackjack')
  const allBust = view.hands.every((hand) => hand.outcome === 'bust')
  const headline =
    result.net > 0 ? (natural ? 'Blackjack' : 'You win') : result.net === 0 ? 'Push' : allBust ? 'Bust' : 'Dealer wins'

  const note = result.refilled
    ? 'Out of chips, so the house has staked you again'
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

/**
 * The blackjack table. State comes from the server through [useBlackjack] and
 * is only ever drawn here.
 */
export function BlackjackTable({ onLeave }: { onLeave?: () => void }) {
  const { view, reveal, connection, error, refusals, send } = useBlackjack()
  const resultShown = useResultShown(view, reveal)

  if (!view) {
    return (
      <CasinoShell>
        <Header detail="Blackjack" onLeave={onLeave} />
        {connection === 'closed' ? (
          <Loading failed message="Could not reach the table. Try again in a minute." />
        ) : (
          <Loading message="Preparing your table" />
        )}
      </CasinoShell>
    )
  }

  const settled = view.phase === 'settled'
  const dealing = settled && !resultShown
  // Cards shrink as hands multiply, so four split hands still fit a phone.
  const size = view.hands.length <= 1 ? 'var(--card-hero)' : view.hands.length === 2 ? 'var(--card-board)' : 'var(--card-opponent)'

  return (
    <CasinoShell showdown={dealing}>
      <Header
        detail={view.roundNumber > 0 ? `Blackjack · ${view.roundNumber}` : 'Blackjack'}
        status={statusOf(view, resultShown)}
        onLeave={onLeave}
      />

      <div className="felt bj-felt mt-2.5 flex flex-1 flex-col">
        <div className="felt__surface flex flex-1 flex-col items-center justify-evenly gap-2 px-2 py-4 short:py-2">
          <Dealer view={view} reveal={reveal} resultShown={resultShown} />

          {/* The middle of the felt carries the house rules, as a real table
              does, and gives way to the result when a round ends. Its height is
              fixed so the cards never shift. */}
          <div className="grid h-14 place-items-center">
            {resultShown ? (
              <Result view={view} />
            ) : (
              <p className="label text-center leading-loose text-gold/45!">
                Blackjack pays 3 to 2
                <br />
                Dealer stands on 17
              </p>
            )}
          </div>

          <section
            aria-label="Your hands"
            className="seat flex flex-col items-center gap-2"
            data-me="true"
            data-acting={view.phase === 'player' || view.phase === 'insurance'}
            data-winner={resultShown && view.result!.net > 0}
          >
            <div className="flex min-h-[calc(var(--card-hero)*1.4+3.75rem)] flex-wrap items-end justify-center gap-x-3 gap-y-2">
              {view.hands.length === 0 && (
                <div className="fan pb-14" style={{ '--w': 'var(--card-hero)' } as CSSProperties}>
                  <CardSlot />
                  <CardSlot />
                </div>
              )}
              {view.hands.map((hand, index) => (
                <Hand key={index} hand={hand} index={index} view={view} resultShown={resultShown} size={size} />
              ))}
            </div>

            <Plate
              seat={0}
              name="You"
              stack={view.stack}
              avatar="Y"
              isMe
              hasButton={false}
              // Winnings count up only once the chips have come back.
              stackDelay={settled ? reveal.delay + 300 : 0}
            />
          </section>
        </div>
      </div>

      {/* A fixed height, so the table never jumps as the controls come and go. */}
      <footer className="mt-2.5 flex min-h-44 flex-col justify-end">
        {connection === 'closed' && (
          <p className="label pb-2 text-center text-gold-bright!">Connection lost. Reload to sit back down.</p>
        )}
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}

        {dealing ? (
          <p className="label pb-8 text-center">The dealer plays</p>
        ) : view.legal.bet ? (
          <>
            <BetControls
              // A fresh round gets fresh controls, and a refused bet gets them back.
              key={`${view.roundNumber}-${refusals}`}
              stack={view.stack}
              minBet={view.minBet}
              maxBet={view.maxBet}
              lastBet={view.lastBet}
              onDeal={(amount) => send({ type: 'bet', amount })}
            />
          </>
        ) : (
          <PlayControls
            key={`${view.roundNumber}-${view.phase}-${view.activeHand}-${view.hands.map((h) => h.cards.length).join('.')}-${refusals}`}
            view={view}
            onAct={(action) => send({ type: 'act', action })}
          />
        )}
      </footer>
    </CasinoShell>
  )
}
