import type { CSSProperties } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { sound } from '../casino/sound'
import type { TableView } from './types'

type HandResultProps = {
  view: TableView
  opponentName: string
  onNextHand: () => void
  onShowReasoning?: () => void
  /** True once Next hand has been pressed and the table is being cleared. */
  leaving?: boolean
}

/**
 * How the hand ended. At a showdown it waits for the cards to turn before
 * saying who won, so the result is seen on the table first and read second.
 */
export function HandResult({ view, opponentName, onNextHand, onShowReasoning, leaving = false }: HandResultProps) {
  const result = view.result!
  const winners = Object.keys(result.winnings).map(Number)
  const mine = result.winnings[view.yourSeat] ?? 0
  const wentToShowdown = Object.keys(result.showdown).length > 0

  const outcome = winners.length > 1 ? 'split' : winners[0] === view.yourSeat ? 'won' : 'lost'
  const amount = outcome === 'lost' ? (result.winnings[winners[0]] ?? 0) : mine

  const headline = { won: 'You win', split: 'Split pot', lost: `${opponentName} wins` }[outcome]
  const me = view.players.find((player) => player.seat === view.yourSeat)!
  const detail = wentToShowdown
    ? Object.entries(result.showdown)
        .map(([seat, hand]) => `${Number(seat) === view.yourSeat ? 'You' : opponentName}: ${hand.replaceAll('_', ' ')}`)
        .join('  ·  ')
    : me.status === 'folded'
      ? 'You folded'
      : `${opponentName} folded`

  const next = () => {
    sound.click()
    onNextHand()
  }

  return (
    <div
      className="rise-in flex flex-col items-center gap-3 text-center"
      style={{ '--rise-delay': wentToShowdown ? '900ms' : '250ms' } as CSSProperties}
    >
      <div aria-live="polite">
        <p className="label">{wentToShowdown ? 'Showdown' : 'Hand over'}</p>
        <p className={`pt-1 text-3xl font-semibold tracking-tight ${outcome === 'won' ? 'text-gold-bright' : 'text-ivory'}`}>
          {headline} <AnimatedNumber value={amount} />
        </p>
        <p className="pt-1 text-sm text-muted capitalize">{detail}</p>
      </div>

      <div className="flex w-full gap-2.5">
        {onShowReasoning && (
          <button type="button" onClick={onShowReasoning} className="btn btn--fold flex-1">
            Reasoning
          </button>
        )}
        <button type="button" onClick={next} disabled={leaving} data-pending={leaving} className="btn btn--raise flex-1">
          Next hand
        </button>
      </div>
    </div>
  )
}
