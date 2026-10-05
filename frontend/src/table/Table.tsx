import { useState } from 'react'
import { CardBack, CardSlot, PlayingCard } from './PlayingCard'
import { ReasoningPanel } from './ReasoningPanel'
import type { ClientMessage, LegalView, PlayerView, TableView } from './types'
import { useTable } from './useTable'

const chips = (amount: number) => amount.toLocaleString('en-US')

export function Table() {
  const { view, reasoning, connection, error, send } = useTable()
  const [showReasoning, setShowReasoning] = useState(false)

  if (!view) {
    return (
      <Shell>
        <p className="m-auto text-white/60">
          {connection === 'closed'
            ? 'Could not reach the table. Is the backend running?'
            : 'Waking up the table…'}
        </p>
      </Shell>
    )
  }

  const me = view.players.find((p) => p.seat === view.yourSeat)!
  const opponent = view.players.find((p) => p.seat !== view.yourSeat)!
  const latestStep = reasoning.events.at(-1)?.label

  return (
    <Shell>
      <header className="flex items-center justify-between text-sm text-white/60">
        <span className="flex items-center gap-2 font-semibold tracking-tight text-white">
          <img src="/logo-192.png" alt="" width={28} height={28} className="h-7 w-7" />
          Banca
        </span>
        <span>
          Hand {view.handNumber} · Blinds {view.smallBlind}/{view.bigBlind}
        </span>
        <button
          type="button"
          onClick={() => setShowReasoning(true)}
          className="rounded-lg bg-black/30 px-3 py-1 text-white hover:bg-black/40"
        >
          Reasoning{reasoning.events.length > 0 && ` (${reasoning.events.length})`}
        </button>
      </header>

      <div className="flex flex-1 flex-col items-center justify-evenly gap-4 py-4">
        <Seat player={opponent} view={view} />
        <Board view={view} />
        <Seat player={me} view={view} isMe />
      </div>

      <footer className="min-h-36">
        {connection === 'closed' && (
          <p className="pb-2 text-center text-sm text-amber-300">Connection lost. Reload to sit back down.</p>
        )}
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-amber-300">
            {error}
          </p>
        )}
        {view.result ? (
          <Result
            view={view}
            onNextHand={() => send({ type: 'next_hand' })}
            onShowReasoning={reasoning.events.length > 0 ? () => setShowReasoning(true) : undefined}
            opponentName={opponent.name}
          />
        ) : view.legal ? (
          <Actions
            // A fresh decision gets fresh controls, so the amount never carries over.
            key={`${view.handNumber}-${view.street}-${view.legal.minRaiseTo}-${view.legal.callCost}`}
            legal={view.legal}
            pot={view.pot}
            committed={me.committed}
            send={send}
          />
        ) : (
          <div aria-live="polite" className="pt-6 text-center text-white/60">
            <p>{opponent.name} is thinking…</p>
            {/* Only the step is shown here; what it found stays private until the hand ends. */}
            {latestStep && <p className="pt-1 text-sm text-white/40">{latestStep}</p>}
          </div>
        )}
      </footer>

      {showReasoning && (
        <ReasoningPanel reasoning={reasoning} name={opponent.name} onClose={() => setShowReasoning(false)} />
      )}
    </Shell>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <main className="min-h-dvh bg-felt-900 text-white">
      <div className="mx-auto flex min-h-dvh max-w-xl flex-col px-4 py-4">{children}</div>
    </main>
  )
}

function Seat({ player, view, isMe = false }: { player: PlayerView; view: TableView; isMe?: boolean }) {
  const isActing = view.actorSeat === player.seat
  const folded = player.status === 'folded'

  return (
    <section
      aria-label={`${player.name}'s seat`}
      className={`flex w-full flex-col items-center gap-2 ${isMe ? 'flex-col-reverse' : ''}`}
    >
      <div
        className={`flex items-center gap-2 rounded-full px-4 py-1.5 text-sm ${
          isActing ? 'bg-chip-gold text-slate-900' : 'bg-black/30'
        }`}
      >
        {view.buttonSeat === player.seat && (
          <span
            title="Dealer button"
            className="grid h-5 w-5 place-items-center rounded-full bg-white text-xs font-bold text-slate-900"
          >
            D
          </span>
        )}
        <span className="font-medium">{player.name}</span>
        <span className="tabular-nums">{chips(player.stack)}</span>
        {player.status === 'all_in' && <span className="font-semibold uppercase">All-in</span>}
        {folded && <span className="uppercase opacity-70">Folded</span>}
      </div>

      <div className={`flex gap-2 ${folded ? 'opacity-40' : ''}`}>
        {player.cards
          ? player.cards.map((card) => <PlayingCard key={card} card={card} />)
          : [0, 1].map((i) => <CardBack key={i} />)}
      </div>

      <p className="h-5 text-sm tabular-nums text-chip-gold">
        {player.committed > 0 && `Bet ${chips(player.committed)}`}
      </p>
    </section>
  )
}

function Board({ view }: { view: TableView }) {
  return (
    <section aria-label="Board" className="flex flex-col items-center gap-3">
      <div className="flex gap-1.5 sm:gap-2">
        {[0, 1, 2, 3, 4].map((i) =>
          view.board[i] ? <PlayingCard key={i} card={view.board[i]} /> : <CardSlot key={i} />,
        )}
      </div>
      <p className="rounded-full bg-black/30 px-4 py-1 text-sm">
        Pot <span className="font-semibold tabular-nums">{chips(view.pot)}</span>
      </p>
    </section>
  )
}

function Actions({
  legal,
  pot,
  committed,
  send,
}: {
  legal: LegalView
  pot: number
  committed: number
  send: (message: ClientMessage) => void
}) {
  const canSize = legal.canBet || legal.canRaise
  const min = legal.canBet ? legal.minBet : legal.minRaiseTo
  const [amount, setAmount] = useState(min)

  const clamp = (value: number) => Math.min(legal.maxTo, Math.max(min, Math.round(value)))
  // A pot-sized raise first calls, then raises by the pot as it would then stand.
  const potSized = legal.canBet ? pot : committed + legal.callCost + pot + legal.callCost
  const sizes = [
    { label: 'Min', value: min },
    { label: '½ pot', value: clamp(potSized / 2) },
    { label: 'Pot', value: clamp(potSized) },
    { label: 'All-in', value: legal.maxTo },
  ]

  const button = 'rounded-xl px-4 py-3 font-semibold transition active:scale-95'

  return (
    <div className="flex flex-col gap-3">
      {canSize && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-3">
            <input
              type="range"
              aria-label="Amount"
              min={min}
              max={legal.maxTo}
              value={amount}
              onChange={(e) => setAmount(clamp(Number(e.target.value)))}
              className="flex-1 accent-chip-gold"
            />
            <input
              type="number"
              aria-label="Amount in chips"
              min={min}
              max={legal.maxTo}
              value={amount}
              onChange={(e) => setAmount(Number(e.target.value))}
              onBlur={() => setAmount(clamp(amount))}
              className="w-24 rounded-lg bg-black/30 px-2 py-1 text-right tabular-nums"
            />
          </div>
          <div className="flex gap-2">
            {sizes.map((size) => (
              <button
                key={size.label}
                type="button"
                onClick={() => setAmount(size.value)}
                className="flex-1 rounded-lg bg-black/30 py-1 text-sm hover:bg-black/40"
              >
                {size.label}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="flex gap-2">
        {!legal.canCheck && (
          <button
            type="button"
            onClick={() => send({ type: 'act', action: 'fold' })}
            className={`${button} flex-1 bg-black/40 hover:bg-black/50`}
          >
            Fold
          </button>
        )}
        {legal.canCheck ? (
          <button
            type="button"
            onClick={() => send({ type: 'act', action: 'check' })}
            className={`${button} flex-1 bg-felt-700 hover:bg-felt-800`}
          >
            Check
          </button>
        ) : (
          <button
            type="button"
            onClick={() => send({ type: 'act', action: 'call' })}
            className={`${button} flex-1 bg-felt-700 hover:bg-felt-800`}
          >
            Call {chips(legal.callCost)}
          </button>
        )}
        {canSize && (
          <button
            type="button"
            onClick={() => send({ type: 'act', action: legal.canBet ? 'bet' : 'raise', amount: clamp(amount) })}
            className={`${button} flex-1 bg-chip-gold text-slate-900 hover:brightness-110`}
          >
            {clamp(amount) === legal.maxTo
              ? `All-in ${chips(legal.maxTo)}`
              : `${legal.canBet ? 'Bet' : 'Raise to'} ${chips(clamp(amount))}`}
          </button>
        )}
      </div>
    </div>
  )
}

function Result({
  view,
  onNextHand,
  onShowReasoning,
  opponentName,
}: {
  view: TableView
  onNextHand: () => void
  onShowReasoning?: () => void
  opponentName: string
}) {
  const result = view.result!
  const winners = Object.keys(result.winnings).map(Number)
  const mine = result.winnings[view.yourSeat] ?? 0
  const nameOf = (seat: number) => view.players.find((p) => p.seat === seat)!.name
  const handOf = (seat: number) => result.showdown[seat]?.replaceAll('_', ' ')

  const headline =
    winners.length > 1
      ? `Split pot, ${chips(mine)} each way`
      : winners[0] === view.yourSeat
        ? `You won ${chips(mine)}`
        : `${nameOf(winners[0])} won ${chips(result.winnings[winners[0]])}`

  const detail = Object.keys(result.showdown).length
    ? view.players.map((p) => `${p.name}: ${handOf(p.seat)}`).join(' · ')
    : 'Won without a showdown'

  return (
    <div className="flex flex-col items-center gap-3 text-center">
      <div aria-live="polite">
        <p className="text-xl font-semibold">{headline}</p>
        <p className="text-sm text-white/60">{detail}</p>
      </div>
      {onShowReasoning && (
        <button type="button" onClick={onShowReasoning} className="text-sm text-chip-gold underline underline-offset-4">
          See how {opponentName} played it
        </button>
      )}
      <button
        type="button"
        onClick={onNextHand}
        className="w-full rounded-xl bg-chip-gold px-4 py-3 font-semibold text-slate-900 hover:brightness-110 active:scale-95"
      >
        Next hand
      </button>
    </div>
  )
}
