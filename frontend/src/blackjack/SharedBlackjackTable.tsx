import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import { CardSlot } from '../casino/Card'
import { CasinoShell } from '../casino/CasinoShell'
import { OutOfChips, StakedNote } from '../casino/ChipNotices'
import { ConnectionNote } from '../casino/ConnectionNote'
import { Header, type Status } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { Plate } from '../casino/Plate'
import { useInviteOffer } from '../casino/useInviteOffer'
import { RoomDrawer } from '../casino/RoomDrawer'
import { sound } from '../casino/sound'
import { useSecondsUntil } from '../casino/useSecondsUntil'
import { BetControls } from './BetControls'
import { CoachPanel, CoachPill, CoachReason } from './Coach'
import { ReviewNote, ReviewPanel } from './Review'
import { useLastReview, type Reviewed } from './grading'
import { PlayControls } from './PlayControls'
import { Dealer, Hand, Result } from './TableParts'
import type { BlackjackTableView, TableSeat } from './types'
import { useResultShown } from './useBlackjack'
import { useBlackjackTable } from './useBlackjackTable'

function statusOf(table: BlackjackTableView, seconds: number): Status {
  switch (table.phase) {
    case 'betting':
      return { text: `Bets · ${seconds}s`, tone: 'gold' }
    case 'insurance':
      return { text: `Insurance · ${seconds}s`, tone: 'gold' }
    case 'playing':
      return table.yourTurn ? { text: `Your turn · ${seconds}s`, tone: 'gold' } : { text: 'Others playing', tone: 'emerald' }
    case 'results':
      return { text: 'Next round', tone: 'quiet' }
  }
}

function signed(amount: number): string {
  return amount === 0 ? 'Even' : `${amount > 0 ? '+' : '−'}${Math.abs(amount).toLocaleString('en-US')}`
}

/** How another player's round stands, in a word or two: what they bet, what they hold, how it ended. */
function standingOf(seat: TableSeat, table: BlackjackTableView): { text: string; tone?: 'gain' | 'loss' | 'even' } {
  if (table.phase === 'results' && seat.net !== null) return { text: signed(seat.net), tone: seat.net > 0 ? 'gain' : seat.net < 0 ? 'loss' : 'even' }
  if (seat.hands.length > 0) {
    return { text: seat.hands.map((hand) => (hand.status === 'bust' ? 'Bust' : hand.status === 'blackjack' ? 'BJ' : String(hand.total))).join(' · ') }
  }
  if (seat.bet > 0) return { text: `Bet ${seat.bet.toLocaleString('en-US')}` }
  return { text: table.phase === 'betting' ? 'Thinking' : 'Sitting out' }
}

/**
 * A blackjack table shared with other players. Everyone faces the same dealer
 * and plays in turn, and the table keeps the time. The player's own cards are
 * drawn exactly as at a table alone; the others sit along the rail above.
 */
export function SharedBlackjackTable({ tableId, onLeave }: { tableId: string; onLeave?: () => void }) {
  const { table, view, reveal, endsAt, waitMs, connection, error, full, refusals, send, chat, phrases, coach, askCoach, broke, staked, retry } =
    useBlackjackTable(tableId)
  const seconds = useSecondsUntil(endsAt)
  const resultShown = useResultShown(view, reveal)
  const [showCoach, setShowCoach] = useState(false)
  const lastReview = useLastReview(view)
  const [reviewing, setReviewing] = useState<Reviewed | null>(null)
  const [showRoom, setShowRoom] = useState(false)
  useInviteOffer(table?.byInvite === true && table.seats.length <= 1, useCallback(() => setShowRoom(true), []))
  const [heard, setHeard] = useState(0)
  // Who this player would rather not hear from. Kept on this device only, for this visit.
  const [muted, setMuted] = useState<Set<string>>(new Set())
  const mute = (name: string) =>
    setMuted((current) => {
      const next = new Set(current)
      if (!next.delete(name)) next.add(name)
      return next
    })

  // The newest thing said is shown for a moment along the rail.
  const latest = chat.findLast((line) => !muted.has(line.from))
  const [quietAfter, setQuietAfter] = useState(0)
  const spoken = chat.length > quietAfter && latest === chat.at(-1)
  useEffect(() => {
    if (chat.length === 0) return
    const timer = setTimeout(() => setQuietAfter(chat.length), 4_000)
    return () => clearTimeout(timer)
  }, [chat.length])

  if (!table || !view) {
    return (
      <CasinoShell>
        <Header detail="Blackjack" onLeave={onLeave} />
        {full ? (
          <Loading failed message="This table is full. Try another, or come back in a moment." />
        ) : connection === 'gone' ? (
          <Loading failed message="This table has closed. Ask for a new link, or open a table of your own." />
        ) : connection === 'closed' || connection === 'replaced' ? (
          <Loading
            failed
            message={connection === 'replaced' ? 'This table is open somewhere else.' : 'Could not reach the table. Try again in a minute.'}
          />
        ) : (
          <Loading message="Finding you a seat" />
        )}
      </CasinoShell>
    )
  }

  const me = table.seats.find((seat) => seat.you)
  const others = table.seats.filter((seat) => !seat.you)
  const inRound = view.hands.length > 0
  const over = table.phase === 'results'
  // The dealer is still turning cards over; the result waits for that.
  const dealing = over && inRound && !resultShown
  const insuring = table.phase === 'insurance' && view.legal.insurance
  const deciding = table.yourTurn || insuring
  const betDown = table.phase === 'betting' && (me?.bet ?? 0) > 0
  const size = view.hands.length <= 1 ? 'var(--card-hero)' : view.hands.length === 2 ? 'var(--card-board)' : 'var(--card-opponent)'
  const unread = chat.length - heard

  return (
    <CasinoShell showdown={dealing}>
      <Header detail={table.name} status={statusOf(table, seconds)} onLeave={onLeave} />

      {/* The rail: who else is at the table and how their round stands. */}
      <div className="table-rail">
        <ul className="table-rail__seats" aria-label="Other players at the table">
          {others.length === 0 && <li className="label py-1.5">{spoken && latest ? '' : 'You have the table to yourself'}</li>}
          {others.map((seat, index) => {
            const standing = standingOf(seat, table)
            return (
              <li key={`${seat.name}-${index}`} className="rail-seat" data-acting={seat.acting}>
                <span className="truncate">{seat.name}</span>
                <span className="figure font-semibold" data-tone={standing.tone}>
                  {standing.text}
                </span>
              </li>
            )
          })}
        </ul>
        <button
          type="button"
          className="icon-button flex-none"
          onClick={() => {
            sound.click()
            setHeard(chat.length)
            setShowRoom(true)
          }}
          aria-label={`Open the table: ${table.seats.length} here${unread > 0 ? `, ${unread} new messages` : ''}`}
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M20 12a8 8 0 01-11.8 7L4 20l1-4.2A8 8 0 1120 12z" />
          </svg>
          {unread > 0 && !showRoom && <span aria-hidden className="icon-button__dot" />}
        </button>
      </div>
      {spoken && latest && !showRoom && (
        <p className="chat-toast rise-in pt-1" key={chat.length}>
          <span className="label tracking-[0.08em]!">{latest.from}</span> {latest.text}
        </p>
      )}

      <div className="felt bj-felt bj-felt--shared mt-2 flex flex-1 flex-col">
        <div className="felt__surface flex flex-1 flex-col items-center justify-evenly gap-2 px-2 py-4 short:py-2">
          <Dealer view={view} reveal={reveal} resultShown={resultShown} />

          {/* A little shorter on a small screen, where the rail above has taken some of the room. */}
          <div className="grid h-14 place-items-center short:h-10">
            {resultShown && view.result ? (
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
            data-acting={deciding}
            data-winner={resultShown && (view.result?.net ?? 0) > 0}
          >
            <div className="flex min-h-[calc(var(--card-hero)*1.4+3.75rem)] flex-wrap items-end justify-center gap-x-3 gap-y-2">
              {!inRound && (
                <div className="fan pb-14" style={{ '--w': 'var(--card-hero)' } as CSSProperties}>
                  <CardSlot />
                  <CardSlot />
                </div>
              )}
              {view.hands.map((hand, index) => (
                <Hand key={index} hand={hand} index={index} view={view} resultShown={resultShown} size={size} />
              ))}
            </div>

            <Plate seat={0} name="You" stack={view.stack} avatar="Y" isMe hasButton={false} stackDelay={over ? reveal.delay + 300 : 0} />
          </section>
        </div>
      </div>

      <footer className="mt-2.5 flex min-h-44 flex-col justify-end short:min-h-36">
        <ConnectionNote connection={connection} />
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}
        {!dealing && <StakedNote amount={staked} />}
        {deciding && coach.advice && <CoachReason advice={coach.advice} />}

        {/* The table's clock for whatever it is waiting on, run down as a line. */}
        {!over && (
          <div className="wait-line" aria-hidden data-mine={deciding || table.phase === 'betting'}>
            <span key={`${table.roundNumber}-${table.phase}-${table.actor}-${waitMs}`} style={{ animationDuration: `${waitMs}ms` }} />
          </div>
        )}

        {/* The last round's review stays to hand through the next betting window: a result is up too briefly to read it in. */}
        {lastReview && !broke && !deciding && !dealing && (table.phase === 'betting' || (over && resultShown)) && (
          <ReviewNote review={lastReview.review} onOpen={() => setReviewing(lastReview)} />
        )}

        {broke ? (
          <OutOfChips broke={broke} onRetry={retry} onLeave={onLeave} />
        ) : dealing ? (
          <p className="label pb-8 text-center">The dealer plays</p>
        ) : deciding ? (
          <PlayControls
            key={`${view.roundNumber}-${view.phase}-${view.activeHand}-${view.hands.map((hand) => hand.cards.length).join('.')}-${refusals}`}
            view={view}
            onAct={(action) => send({ type: 'act', action })}
            advised={coach.advice?.action}
          />
        ) : table.phase === 'betting' ? (
          betDown ? (
            <div className="rise-in flex flex-col items-center gap-3 pb-2">
              <p className="text-center text-sm text-ivory/85">
                Your bet of <span className="figure font-semibold text-gold-bright">{me!.bet.toLocaleString('en-US')}</span> is down. Cards in{' '}
                {seconds}s.
              </p>
              <button type="button" className="btn btn--quiet px-5! text-sm" onClick={() => send({ type: 'bet', amount: 0 })}>
                Take it back
              </button>
            </div>
          ) : (
            <BetControls
              // A fresh round gets fresh controls, and a refused bet gets them back.
              key={`${table.roundNumber}-${refusals}`}
              stack={view.stack}
              minBet={view.minBet}
              maxBet={view.maxBet}
              lastBet={view.lastBet}
              onDeal={(amount) => send({ type: 'bet', amount })}
              verb="Bet"
            />
          )
        ) : (
          <p className="label pb-8 text-center leading-relaxed">
            {over
              ? `Next round in ${seconds}s`
              : !inRound
                ? 'You are sitting this round out'
                : table.phase === 'insurance'
                  ? 'Waiting for the others to answer'
                  : table.actor
                    ? `${table.actor} is playing`
                    : 'Waiting for the dealer'}
          </p>
        )}
      </footer>

      {reviewing && <ReviewPanel reviewed={reviewing} onClose={() => setReviewing(null)} />}
      {showCoach && deciding && coach.status !== 'idle' && <CoachPanel coach={coach} roundNumber={view.roundNumber} onClose={() => setShowCoach(false)} />}
      {showRoom && (
        <RoomDrawer
          name={table.name}
          byInvite={table.byInvite}
          players={table.seats.map((seat) => ({ name: seat.name, staked: seat.bet, net: seat.net, you: seat.you }))}
          chat={chat}
          phrases={phrases}
          onSay={(say) => send({ type: 'chat', say })}
          onType={(text) => send({ type: 'chat', text })}
          muted={muted}
          onMute={mute}
          onClose={() => {
            setHeard(chat.length)
            setShowRoom(false)
          }}
        />
      )}
    </CasinoShell>
  )
}
