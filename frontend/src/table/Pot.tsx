import { AnimatedNumber } from '../casino/AnimatedNumber'
import { ChipStack } from '../casino/Chip'
import type { TableView } from './types'
import { TIMING, type Presentation } from './usePresentation'

/**
 * The pot: where swept bets land, and where a winner's chips leave from. It is
 * the anchor of the table, so it answers when chips reach it.
 */
export function Pot({ view, pulse }: { view: TableView; pulse: Presentation['potPulse'] }) {
  const paidOut = view.result !== null

  return (
    <div data-anchor="pot" className="pot glass glass--strong flex h-10 items-center gap-2.5 rounded-full pr-5 pl-3">
      {/* A new element each time, so the ring plays again from the start. */}
      {pulse.count > 0 && <span key={pulse.count} aria-hidden className="pot__pulse" data-big={pulse.big} />}

      <span className="grid h-6 w-6 place-items-center">
        {view.pot > 0 && !paidOut && <ChipStack amount={view.pot} bigBlind={view.bigBlind} size={21} />}
      </span>
      <span className="label">Pot</span>
      <span className="text-xl font-semibold tracking-tight text-ivory">
        {/* Once the hand is won the pot empties as its chips leave for the winner. */}
        <AnimatedNumber value={paidOut ? 0 : view.pot} delay={paidOut ? TIMING.PAYOUT_AT : 0} />
      </span>
    </div>
  )
}
