import { useState, type CSSProperties } from 'react'
import { CardSlot } from '../casino/Card'
import { CasinoShell } from '../casino/CasinoShell'
import { Header, type Status } from '../casino/Header'
import { OutOfChips, StakedNote } from '../casino/ChipNotices'
import { ConnectionNote } from '../casino/ConnectionNote'
import { Loading } from '../casino/Loading'
import { CoachPanel, CoachPill, CoachReason } from './Coach'
import { ReviewNote, ReviewPanel } from './Review'
import type { Reviewed } from './grading'
import { Plate } from '../casino/Plate'
import { BetControls } from './BetControls'
import { PlayControls } from './PlayControls'
import type { BlackjackView } from './types'
import { Dealer, Hand, Result } from './TableParts'
import { useBlackjack, useResultShown } from './useBlackjack'
import { Guide } from '../guide/Guide'
import { useGuide } from '../guide/useGuide'

function statusOf(view: BlackjackView, resultShown: boolean): Status {
  switch (view.phase) {
    case 'betting':
      return { text: 'Your bet', tone: 'gold' }
    case 'insurance':
      return { text: 'Insurance?', tone: 'gold' }
    case 'player':
    // Only at a shared table, which has a status of its own.
    case 'waiting':
      return { text: 'Your turn', tone: 'gold' }
    case 'settled':
      return resultShown ? { text: 'Your bet', tone: 'gold' } : { text: 'Dealer plays', tone: 'emerald' }
  }
}

/**
 * The blackjack table. State comes from the server through [useBlackjack] and
 * is only ever drawn here.
 */
export function BlackjackTable({ onLeave }: { onLeave?: () => void }) {
  const { view, reveal, connection, error, refusals, send, broke, staked, retry, coach, askCoach } = useBlackjack()
  const [showCoach, setShowCoach] = useState(false)
  const [reviewing, setReviewing] = useState<Reviewed | null>(null)
  const resultShown = useResultShown(view, reveal)
  // Banca's walk through a first round follows the round: before the cards, while the hand is played, once it is settled.
  const guide = useGuide(
    'blackjack',
    view && {
      round: view.roundNumber,
      stage: view.phase === 'betting' ? 0 : view.phase === 'settled' && resultShown ? 2 : 1,
      idle: view.legal.bet,
      facts: { insurance: view.phase === 'insurance', deciding: view.phase === 'player' },
    },
  )

  if (!view) {
    return (
      <CasinoShell>
        <Header detail="Blackjack" onLeave={onLeave} />
        {connection === 'closed' || connection === 'replaced' ? (
          <Loading
            failed
            message={connection === 'replaced' ? 'This table is open somewhere else.' : 'Could not reach the table. Try again in a minute.'}
          />
        ) : (
          <Loading message="Preparing your table" />
        )}
      </CasinoShell>
    )
  }

  const settled = view.phase === 'settled'
  const dealing = settled && !resultShown
  // The coach is there to be asked whenever the player has something to decide.
  const deciding = view.phase === 'player' || view.phase === 'insurance'
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
            ) : deciding ? (
              <CoachPill coach={coach} onAsk={askCoach} onOpen={() => setShowCoach(true)} />
            ) : (
              <p className="label text-center leading-loose text-gold/75!">
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
        <ConnectionNote connection={connection} />
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}

        {!dealing && <StakedNote amount={staked} />}
        {deciding && coach.advice && <CoachReason advice={coach.advice} />}

        {dealing ? (
          <p className="label pb-8 text-center">The dealer plays</p>
        ) : broke ? (
          // Trying again puts the bet controls back; the next bet asks the house afresh.
          <OutOfChips broke={broke} onRetry={retry} onLeave={onLeave} />
        ) : view.legal.bet ? (
          <>
            {resultShown && view.review && (
              <ReviewNote review={view.review} onOpen={() => setReviewing({ review: view.review!, roundNumber: view.roundNumber })} />
            )}
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
            advised={coach.advice?.action}
          />
        )}
      </footer>

      {/* Put away while the dealer turns cards over, which is the thing to watch. */}
      <Guide guide={guide} quiet={dealing} />
      {reviewing && <ReviewPanel reviewed={reviewing} onClose={() => setReviewing(null)} />}
      {showCoach && deciding && coach.status !== 'idle' && (
        <CoachPanel coach={coach} roundNumber={view.roundNumber} onClose={() => setShowCoach(false)} />
      )}
    </CasinoShell>
  )
}
