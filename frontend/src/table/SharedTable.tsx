import { useEffect, useState, type CSSProperties } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { Card } from '../casino/Card'
import { CasinoShell } from '../casino/CasinoShell'
import { ChipStack } from '../casino/Chip'
import { OutOfChips, StakedNote } from '../casino/ChipNotices'
import { ConnectionNote } from '../casino/ConnectionNote'
import { Header, type Status } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { WaitingRoom } from '../casino/WaitingRoom'
import { RoomDrawer } from '../casino/RoomDrawer'
import { sound } from '../casino/sound'
import { useSecondsUntil } from '../casino/useSecondsUntil'
import { ActionBar } from './ActionBar'
import { AgentThinking } from './AgentThinking'
import { BancaSays } from './BancaSays'
import { Board } from './Board'
import { Pot } from './Pot'
import { PokerCoachPanel, PokerCoachPill, PokerCoachReason } from './PokerCoach'
import { ReasoningPanel } from './ReasoningPanel'
import { Seat } from './Seat'
import { ShareHandButton, ShareHandPanel } from '../share/ShareHand'
import { handCardOf, type HandCard } from '../share/handCard'
import type { PlayerView, PokerRoomView, TableView } from './types'
import { usePokerRoom } from './usePokerRoom'
import { TIMING, usePresentation } from './usePresentation'

/** Banca always has the first seat at a shared table. */
const BANCA_SEAT = 0

function statusOf(room: PokerRoomView, seconds: number): Status {
  if (room.phase === 'results') return { text: 'Next hand', tone: 'quiet' }
  if (room.phase === 'waiting') return { text: 'Waiting', tone: 'quiet' }
  if (room.yourTurn) return { text: `Your turn · ${seconds}s`, tone: 'gold' }
  if (room.actor === 'Banca') return { text: 'AI thinking', tone: 'emerald' }
  return { text: room.actor ? `${room.actor}'s turn` : 'Dealing', tone: 'emerald' }
}

/**
 * Another player's place at a shared table, small enough for five of them to
 * sit in a row: their two cards, name and stack, and what they have put
 * forward this street.
 */
function SmallSeat({ player, view, action }: { player: PlayerView; view: TableView; action?: string }) {
  const result = view.result
  const folded = player.status === 'folded'
  const winner = result !== null && player.seat in result.winnings
  const hand = result?.showdown[player.seat]?.replaceAll('_', ' ')
  const inFront = result ? 0 : player.committed

  return (
    <section
      aria-label={`${player.name}'s seat`}
      className="seat small-seat"
      data-me="false"
      data-acting={view.actorSeat === player.seat}
      data-winner={winner}
      data-loser={result !== null && !winner && !folded}
      data-folded={folded}
    >
      <div className="seat__cards flex justify-center gap-1" style={{ '--w': 'var(--card-small)' } as CSSProperties}>
        {[0, 1].map((index) => (
          <Card
            key={`${view.handNumber}-${index}`}
            card={player.cards?.[index] ?? null}
            dealDelay={(index * 2 + player.seat) * 90}
            dealFrom={{ x: '0px', y: '14vh' }}
            flipDelay={350 + index * 150}
            seed={view.handNumber * 10 + player.seat * 2 + index}
          />
        ))}
      </div>

      <p className="small-seat__name" data-anchor={`stack-${player.seat}`}>
        {view.buttonSeat === player.seat && (
          <span className="small-seat__button" title="Dealer button" aria-label="Dealer button">
            D
          </span>
        )}
        <span className="truncate">{player.name}</span>
      </p>
      <p className="figure text-xs font-semibold text-ivory">
        {player.status === 'all_in' ? (
          <span className="text-gold-bright">All-in</span>
        ) : (
          <AnimatedNumber value={player.stack} delay={result ? TIMING.PAYOUT_AT + 300 : 0} />
        )}
      </p>

      <p className="flex h-5 items-center justify-center gap-1">
        <span data-anchor={`bet-${player.seat}`} className="grid h-4 w-4 place-items-center">
          {inFront > 0 && <ChipStack amount={inFront} bigBlind={view.bigBlind} size={14} />}
        </span>
        {inFront > 0 ? (
          <span className="figure text-xs font-semibold text-ivory">{inFront.toLocaleString('en-US')}</span>
        ) : hand ? (
          <span className={`label tracking-[0.06em]! ${winner ? 'text-gold-bright!' : ''}`}>{hand}</span>
        ) : (
          action && !result && <span className="label tracking-[0.06em]! text-gold!">{folded ? 'Fold' : action.split(' ')[0]}</span>
        )}
      </p>
    </section>
  )
}

/** How a hand at a shared table ended, said for whoever is reading it. */
function Outcome({ view, seconds, onShowReasoning, onShare }: { view: TableView; seconds: number; onShowReasoning?: () => void; onShare?: () => void }) {
  const result = view.result!
  const winners = Object.keys(result.winnings).map(Number)
  const nameOf = (seat: number) => (seat === view.yourSeat ? 'You' : (view.players.find((player) => player.seat === seat)?.name ?? 'Someone'))
  const wentToShowdown = Object.keys(result.showdown).length > 0
  const iWon = view.yourSeat in result.winnings

  const headline = winners.length > 1 ? 'Split pot' : winners[0] === view.yourSeat ? 'You win' : `${nameOf(winners[0])} wins`
  const amount = iWon ? result.winnings[view.yourSeat] : (result.winnings[winners[0]] ?? 0)
  const detail = wentToShowdown
    ? winners.map((seat) => `${nameOf(seat)}: ${result.showdown[seat]?.replaceAll('_', ' ') ?? ''}`).join('  ·  ')
    : 'Everyone else folded'

  return (
    <div className="rise-in flex flex-col items-center gap-3 text-center" style={{ '--rise-delay': wentToShowdown ? '900ms' : '250ms' } as CSSProperties}>
      <div aria-live="polite">
        <p className="label">{wentToShowdown ? 'Showdown' : 'Hand over'}</p>
        <p className={`pt-1 text-3xl font-semibold tracking-tight ${iWon ? 'text-gold-bright' : 'text-ivory'}`}>
          {headline} <AnimatedNumber value={amount} />
        </p>
        <p className="pt-1 text-sm text-muted capitalize">{detail}</p>
      </div>
      <div className="flex w-full items-center gap-2.5">
        {onShowReasoning && (
          <button type="button" onClick={onShowReasoning} className="btn btn--fold flex-1">
            Banca's reasoning
          </button>
        )}
        {onShare && <ShareHandButton onOpen={onShare} />}
        <p className="label flex-1 text-center">Next hand in {seconds}s</p>
      </div>
    </div>
  )
}

/**
 * A poker table shared with other players, with Banca in one seat. The table
 * deals hand after hand and keeps the time. The player's own cards and
 * controls are as at a table alone; everyone else sits in a row across the top.
 */
export function SharedTable({ tableId, onLeave }: { tableId: string; onLeave?: () => void }) {
  const { room, endsAt, waitMs, reasoning, connection, error, full, refusals, send, chat, said, phrases, broke, staked, retry, coach, askCoach } =
    usePokerRoom(tableId)
  const view = room?.table ?? null
  const { actions, showdown, potPulse } = usePresentation(view)
  const seconds = useSecondsUntil(endsAt)
  const [showReasoning, setShowReasoning] = useState(false)
  const [showCoach, setShowCoach] = useState(false)
  // The hand being shared, kept as it was when asked for: the table deals the next one without waiting.
  const [sharing, setSharing] = useState<HandCard | null>(null)
  const [showRoom, setShowRoom] = useState(false)
  const [heard, setHeard] = useState(0)
  // Who this player would rather not hear from. Kept on this device only, for this visit.
  const [muted, setMuted] = useState<Set<string>>(new Set())
  const mute = (name: string) =>
    setMuted((current) => {
      const next = new Set(current)
      if (!next.delete(name)) next.add(name)
      return next
    })

  // The newest thing said is shown for a moment above the controls.
  const latest = chat.findLast((line) => !muted.has(line.from))
  const [quietAfter, setQuietAfter] = useState(0)
  const spoken = chat.length > quietAfter && latest === chat.at(-1)
  // Banca waits for the cards to turn before it speaks, so it is given longer to be read.
  const readFor = chat.at(-1)?.banca ? 6_500 : 4_000
  useEffect(() => {
    if (chat.length === 0) return
    const timer = setTimeout(() => setQuietAfter(chat.length), readFor)
    return () => clearTimeout(timer)
  }, [chat.length, readFor])

  // A private table before its host has started the game: who is here, and the link to bring the rest.
  if (room && room.byInvite && !room.started && !broke && connection !== 'gone') {
    const here = room.seats.length
    return (
      <CasinoShell>
        <Header detail="Texas Hold'em" status={{ text: 'Not started', tone: 'quiet' }} onLeave={onLeave} />
        <ConnectionNote connection={connection} />
        {error && (
          <p role="alert" className="pt-3 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}
        <WaitingRoom
          table={room.name}
          seats={room.seats.map((seat) => ({ name: seat.name, you: seat.you, host: seat.name === room.host, house: seat.seat === BANCA_SEAT }))}
          seatsInAll={room.seatsInAll}
          host={room.host}
          youHost={room.youHost}
          notYet={here < 2 ? 'Poker needs two. Waiting for someone to join' : null}
          onStart={() => send({ type: 'start' })}
          code={room.room}
          practice={room.practice}
          onEnd={room.youHost ? () => send({ type: 'end' }) : undefined}
        >
          <button
            type="button"
            className="btn btn--quiet px-4! py-2! text-sm"
            onClick={() => {
              setHeard(chat.length)
              setShowRoom(true)
            }}
          >
            Chat{chat.length - heard > 0 ? ` · ${chat.length - heard}` : ''}
          </button>
        </WaitingRoom>
        {showRoom && (
          <RoomDrawer
            name={room.name}
            byInvite
            code={room.room}
            practice={room.practice}
            onEnd={room.youHost ? () => send({ type: 'end' }) : undefined}
            players={room.seats.map((seat) => ({ name: seat.name, staked: 0, net: null, you: seat.you }))}
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

  // A table its host has closed is gone, whatever was on the felt a moment ago.
  if (!room || (!view && !broke) || connection === 'gone') {
    return (
      <CasinoShell>
        <Header detail="Texas Hold'em" onLeave={onLeave} />
        {full ? (
          <Loading failed message="This table is full. Try another, or come back in a moment." />
        ) : connection === 'gone' ? (
          <Loading failed message="This table has closed. Ask for a new code, or open a table of your own." />
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

  // Out of chips before a hand has ever been dealt here: there is no table to draw yet.
  if (!view) {
    return (
      <CasinoShell>
        <Header detail={room.name} onLeave={onLeave} />
        <div className="m-auto w-full max-w-md">
          <OutOfChips broke={broke!} onRetry={retry} onLeave={onLeave} />
        </div>
      </CasinoShell>
    )
  }

  const me = room.dealtIn ? view.players.find((player) => player.seat === view.yourSeat) : undefined
  // Everyone else, clockwise from the player's own seat, as they would sit round a table.
  const mySeat = me?.seat ?? room.seats.find((seat) => seat.you)?.seat ?? 99
  const others = view.players
    .filter((player) => player.seat !== me?.seat)
    .sort((a, b) => ((a.seat - mySeat + 99) % 99) - ((b.seat - mySeat + 99) % 99))
  const bancaThinking = room.phase === 'playing' && view.actorSeat === BANCA_SEAT
  const over = room.phase === 'results' && view.result !== null
  const shareable = over ? handCardOf(view, said) : null
  const unread = chat.length - heard
  const waiting = room.seats.filter((seat) => !seat.inHand && seat.seat !== BANCA_SEAT)

  return (
    <CasinoShell showdown={showdown}>
      <Header detail={room.name} status={statusOf(room, seconds)} onLeave={onLeave} />

      <div className="felt mt-2.5 flex flex-1 flex-col">
        {/* The way into the chat sits in the corner of the felt, taking no room from the cards. */}
        <button
          type="button"
          className="icon-button felt__corner"
          onClick={() => {
            sound.click()
            setHeard(chat.length)
            setShowRoom(true)
          }}
          aria-label={`Open the table: ${room.seats.length} here${unread > 0 ? `, ${unread} new messages` : ''}`}
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M20 12a8 8 0 01-11.8 7L4 20l1-4.2A8 8 0 1120 12z" />
          </svg>
          {unread > 0 && !showRoom && <span aria-hidden className="icon-button__dot" />}
        </button>

        <div className="felt__surface flex flex-1 flex-col items-center justify-evenly gap-1.5 px-2 py-3.5 short:py-2">
          {others.length === 1 ? (
            <Seat player={others[0]} view={view} action={actions[others[0].seat]} />
          ) : (
            <div className="small-seats">
              {others.map((player) => (
                <SmallSeat key={player.seat} player={player} view={view} action={actions[player.seat]} />
              ))}
            </div>
          )}

          <div className="flex flex-col items-center gap-1.5 short:flex-row short:gap-2">
            {room.seats.some((seat) => seat.seat === BANCA_SEAT) && (
              <AgentThinking
                reasoning={reasoning}
                thinking={bancaThinking}
                decided={view.result ? undefined : actions[BANCA_SEAT]}
                onOpen={() => setShowReasoning(true)}
              />
            )}
            <Pot view={view} pulse={potPulse} />
          </div>

          <Board view={view} />

          {me ? (
            <Seat player={me} view={view} isMe action={actions[me.seat]} />
          ) : (
            <div className="grid min-h-[calc(var(--card-hero)*1.4+4.5rem)] place-items-center">
              <p className="label text-center leading-relaxed text-gold/60!">
                You are watching this hand
                <br />
                and will be dealt into the next
              </p>
            </div>
          )}
        </div>
      </div>

      {/* A fixed height, so the table never jumps as the controls come and go. */}
      <footer className="mt-2.5 flex min-h-40 flex-col justify-end">
        <ConnectionNote connection={connection} />
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}
        <StakedNote amount={staked} />
        {spoken && latest && !showRoom &&
          (latest.banca ? (
            <BancaSays key={chat.length} text={latest.text} afterShowdown={Object.keys(view.result?.showdown ?? {}).length > 0} />
          ) : (
            <p className="chat-toast rise-in pb-2" key={chat.length}>
              <span className="label tracking-[0.08em]!">{latest.from}</span> {latest.text}
            </p>
          ))}

        {/* How long the table will wait for whoever it is waiting on. */}
        {room.phase === 'playing' && view.actorSeat !== null && view.actorSeat !== BANCA_SEAT && (
          <div className="wait-line" aria-hidden data-mine={room.yourTurn}>
            <span key={`${view.handNumber}-${view.actorSeat}-${view.street}-${waitMs}`} style={{ animationDuration: `${waitMs}ms` }} />
          </div>
        )}

        {broke ? (
          <OutOfChips broke={broke} onRetry={retry} onLeave={onLeave} />
        ) : over ? (
          <Outcome
            view={view}
            seconds={seconds}
            onShowReasoning={reasoning.events.length > 0 ? () => setShowReasoning(true) : undefined}
            onShare={shareable ? () => setSharing(shareable) : undefined}
          />
        ) : room.yourTurn && view.legal && me ? (
          <>
            <PokerCoachReason coach={coach} />
            <ActionBar
              // A fresh decision gets fresh controls, so an amount never carries over.
              key={`${view.handNumber}-${view.street}-${view.legal.minRaiseTo}-${view.legal.callCost}-${refusals}`}
              legal={view.legal}
              pot={view.pot}
              committed={me.committed}
              send={send}
              advised={coach.advice}
              coach={<PokerCoachPill coach={coach} onAsk={askCoach} onOpen={() => setShowCoach(true)} />}
            />
          </>
        ) : (
          <p className="label pb-6 text-center leading-relaxed">
            {room.phase === 'waiting'
              ? room.seats.length < 2
                ? 'Waiting for someone to play against'
                : 'Waiting for the next hand'
              : !me
                ? `${waiting.length > 1 ? `${waiting.length} players` : 'You'} waiting for the next hand`
                : me.status === 'folded'
                  ? 'You folded. The hand plays on'
                  : room.actor
                    ? `${room.actor} is deciding`
                    : 'Dealing'}
          </p>
        )}
      </footer>

      {showCoach && room.yourTurn && view && coach.status !== 'idle' && (
        <PokerCoachPanel coach={coach} handNumber={view.handNumber} onClose={() => setShowCoach(false)} />
      )}
      {sharing && <ShareHandPanel card={sharing} onClose={() => setSharing(null)} />}
      {showReasoning && <ReasoningPanel reasoning={reasoning} name="Banca" thinking={bancaThinking} onClose={() => setShowReasoning(false)} />}
      {showRoom && (
        <RoomDrawer
          name={room.name}
          byInvite={room.byInvite}
          code={room.room}
          practice={room.practice}
          onEnd={room.byInvite && room.youHost ? () => send({ type: 'end' }) : undefined}
          players={room.seats.map((seat) => {
            const player = view.players.find((inHand) => inHand.seat === seat.seat)
            const won = view.result?.winnings[seat.seat]
            return { name: seat.name, staked: seat.inHand && player ? player.stack : 0, net: over && won ? won : null, you: seat.you }
          })}
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
