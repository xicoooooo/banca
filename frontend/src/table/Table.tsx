import { useState } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header, type Status } from '../casino/Header'
import { OutOfChips, StakedNote } from '../casino/ChipNotices'
import { ConnectionNote } from '../casino/ConnectionNote'
import { Loading } from '../casino/Loading'
import { prefersReducedMotion } from '../casino/motion'
import { ActionBar } from './ActionBar'
import { AgentThinking } from './AgentThinking'
import { Board } from './Board'
import { HandResult } from './HandResult'
import { Pot } from './Pot'
import { PokerCoachPanel, PokerCoachPill, PokerCoachReason } from './PokerCoach'
import { ReasoningPanel } from './ReasoningPanel'
import { Seat } from './Seat'
import type { TableView } from './types'
import { usePresentation } from './usePresentation'
import { useTable } from './useTable'
import { Guide } from '../guide/Guide'
import { useGuide } from '../guide/useGuide'

/**
 * The poker table. State comes from the server through [useTable] and is only
 * ever drawn here; [usePresentation] adds the movement and sound on top.
 */
function statusOf(view: TableView): Status {
  if (view.result) return { text: 'Hand over', tone: 'quiet' }
  if (view.actorSeat === view.yourSeat) return { text: 'Your turn', tone: 'gold' }
  if (view.actorSeat !== null) return { text: 'AI thinking', tone: 'emerald' }
  return { text: 'Dealing', tone: 'quiet' }
}

export function Table({ onLeave }: { onLeave?: () => void }) {
  const { view, reasoning, connection, error, refusals, send, broke, staked, coach, askCoach } = useTable()
  const { actions, showdown, potPulse } = usePresentation(view)
  const [showReasoning, setShowReasoning] = useState(false)
  const [showCoach, setShowCoach] = useState(false)
  // The hand being cleared away, if Next hand has just been pressed.
  const [clearing, setClearing] = useState<number | null>(null)
  // Banca's walk through a first hand: while it is played, and once it is over.
  const guide = useGuide('poker', view && { round: view.handNumber, stage: view.result ? 1 : 0, idle: view.result !== null })

  if (!view) {
    return (
      <CasinoShell>
        <Header detail="Texas Hold'em" onLeave={onLeave} />
        {connection === 'closed' || connection === 'replaced' ? (
          <Loading
            failed
            message={connection === 'replaced' ? 'This table is open somewhere else.' : 'Could not reach the table. Try again in a minute.'}
          />
        ) : broke ? (
          // Nothing has been dealt, because there is nothing to post a blind with.
          <div className="m-auto w-full max-w-md">
            <OutOfChips broke={broke} onRetry={() => send({ type: 'next_hand' })} onLeave={onLeave} />
          </div>
        ) : (
          <Loading message="Preparing your table" />
        )}
      </CasinoShell>
    )
  }

  const me = view.players.find((player) => player.seat === view.yourSeat)!
  const opponent = view.players.find((player) => player.seat !== view.yourSeat)!
  const opponentThinking = view.actorSeat === opponent.seat
  const leaving = clearing === view.handNumber

  // The cards and chips are gathered up before the next hand is asked for, so
  // one hand runs into the next without a cut. The wait is the player's own
  // button press, never a state from the server.
  const nextHand = () => {
    if (prefersReducedMotion()) return send({ type: 'next_hand' })
    setClearing(view.handNumber)
    setTimeout(() => send({ type: 'next_hand' }), 260)
  }

  return (
    <CasinoShell showdown={showdown}>
      <Header
        detail={`Hand ${view.handNumber} · ${view.smallBlind}/${view.bigBlind}`}
        status={statusOf(view)}
        onLeave={onLeave}
      />

      <div className="felt mt-2.5 flex flex-1 flex-col" data-leaving={leaving}>
        <div className="felt__surface flex flex-1 flex-col items-center justify-evenly gap-1.5 px-2 py-3.5 short:py-2">
          <Seat player={opponent} view={view} />

          {/* Stacked where there is room, side by side on a short screen. */}
          <div className="flex flex-col items-center gap-1.5 short:flex-row short:gap-2">
            <AgentThinking
              reasoning={reasoning}
              thinking={opponentThinking}
              decided={view.result ? undefined : actions[opponent.seat]}
              onOpen={() => setShowReasoning(true)}
            />
            <Pot view={view} pulse={potPulse} />
          </div>

          <Board view={view} />

          <Seat player={me} view={view} isMe action={actions[me.seat]} />
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

        {broke ? (
          <OutOfChips broke={broke} onRetry={() => send({ type: 'next_hand' })} onLeave={onLeave} />
        ) : view.result ? (
          <HandResult
            view={view}
            opponentName={opponent.name}
            onNextHand={nextHand}
            leaving={leaving}
            onShowReasoning={reasoning.events.length > 0 ? () => setShowReasoning(true) : undefined}
          />
        ) : view.legal ? (
          <>
            <PokerCoachReason coach={coach} />
            <ActionBar
              // A fresh decision gets fresh controls, so an amount never carries over.
              // A refusal means the last action did not stand, so the controls come back.
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
          <p className="label pb-6 text-center">{opponent.name} is deciding</p>
        )}
      </footer>

      <Guide guide={guide} />
      {showCoach && view.legal && coach.status !== 'idle' && (
        <PokerCoachPanel coach={coach} handNumber={view.handNumber} onClose={() => setShowCoach(false)} />
      )}
      {showReasoning && (
        <ReasoningPanel
          reasoning={reasoning}
          name={opponent.name}
          thinking={opponentThinking}
          onClose={() => setShowReasoning(false)}
        />
      )}
    </CasinoShell>
  )
}
