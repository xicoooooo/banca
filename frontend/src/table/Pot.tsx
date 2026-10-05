import { AnimatedNumber } from '../casino/AnimatedNumber'
import { ChipStack } from '../casino/Chip'
import type { TableView } from './types'
import { TIMING } from './usePresentation'

/** The pot: where swept bets land, and where a winner's chips leave from. */
export function Pot({ view }: { view: TableView }) {
  const paidOut = view.result !== null

  return (
    <div data-anchor="pot" className="glass glass--strong flex h-9 items-center gap-2.5 rounded-full pr-4 pl-2.5">
      <span className="grid h-6 w-6 place-items-center">
        {view.pot > 0 && !paidOut && <ChipStack amount={view.pot} bigBlind={view.bigBlind} size={20} />}
      </span>
      <span className="label">Pot</span>
      <span className="text-lg font-semibold text-ivory">
        {/* Once the hand is won the pot empties as its chips leave for the winner. */}
        <AnimatedNumber value={paidOut ? 0 : view.pot} delay={paidOut ? TIMING.PAYOUT_AT : 0} />
      </span>
    </div>
  )
}
