import { useState } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Loading } from '../casino/Loading'
import { ActionBar } from './ActionBar'
import { AgentThinking } from './AgentThinking'
import { Board } from './Board'
import { HandResult } from './HandResult'
import { Header } from './Header'
import { Pot } from './Pot'
import { ReasoningPanel } from './ReasoningPanel'
import { Seat } from './Seat'
import { usePresentation } from './usePresentation'
import { useTable } from './useTable'

/**
 * The poker table. State comes from the server through [useTable] and is only
 * ever drawn here; [usePresentation] adds the movement and sound on top.
 */
export function Table() {
  const { view, reasoning, connection, error, send } = useTable()
  const { actions, showdown } = usePresentation(view)
  const [showReasoning, setShowReasoning] = useState(false)

  if (!view) {
    return (
      <CasinoShell>
        {connection === 'closed' ? (
          <Loading failed message="Could not reach the table. Try again in a minute." />
        ) : (
          <Loading message="Preparing your table" />
        )}
      </CasinoShell>
    )
  }

  const me = view.players.find((player) => player.seat === view.yourSeat)!
  const opponent = view.players.find((player) => player.seat !== view.yourSeat)!
  const opponentThinking = view.actorSeat === opponent.seat

  return (
    <CasinoShell showdown={showdown}>
      <Header view={view} />

      <div className="felt mt-2.5 flex flex-1 flex-col">
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
            <Pot view={view} />
          </div>

          <Board view={view} />

          <Seat player={me} view={view} isMe action={actions[me.seat]} />
        </div>
      </div>

      {/* A fixed height, so the table never jumps as the controls come and go. */}
      <footer className="mt-2.5 flex min-h-40 flex-col justify-end">
        {connection === 'closed' && (
          <p className="label pb-2 text-center text-gold-bright!">Connection lost. Reload to sit back down.</p>
        )}
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}

        {view.result ? (
          <HandResult
            view={view}
            opponentName={opponent.name}
            onNextHand={() => send({ type: 'next_hand' })}
            onShowReasoning={reasoning.events.length > 0 ? () => setShowReasoning(true) : undefined}
          />
        ) : view.legal ? (
          <ActionBar
            // A fresh decision gets fresh controls, so an amount never carries over.
            key={`${view.handNumber}-${view.street}-${view.legal.minRaiseTo}-${view.legal.callCost}`}
            legal={view.legal}
            pot={view.pot}
            committed={me.committed}
            send={send}
          />
        ) : (
          <p className="label pb-6 text-center">{opponent.name} is deciding</p>
        )}
      </footer>

      {showReasoning && (
        <ReasoningPanel reasoning={reasoning} name={opponent.name} onClose={() => setShowReasoning(false)} />
      )}
    </CasinoShell>
  )
}
