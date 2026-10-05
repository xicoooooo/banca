import type { CSSProperties } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { Card } from '../casino/Card'
import { ChipStack } from '../casino/Chip'
import type { PlayerView, TableView } from './types'
import { TIMING } from './usePresentation'

type SeatProps = {
  player: PlayerView
  view: TableView
  isMe?: boolean
  /** The last thing this player did this street, such as "Raise to 120". */
  action?: string
}

/**
 * A place at the table: the player's cards, the chips they have put forward
 * this street, and a plate with their name and stack. The opponent sits at the
 * far side, so their seat is laid out as the mirror image of yours.
 */
export function Seat({ player, view, isMe = false, action }: SeatProps) {
  const result = view.result
  const acting = view.actorSeat === player.seat
  const folded = player.status === 'folded'
  const winner = result !== null && player.seat in result.winnings
  const hand = result?.showdown[player.seat]?.replaceAll('_', ' ')

  // Cards are dealt from the middle of the table, where the house deals from.
  const dealFrom = isMe ? { x: '0px', y: '-24vh' } : { x: '0px', y: '18vh' }
  const seatOrder = isMe ? 1 : 0

  const cards = (
    <div
      className="seat__cards flex justify-center gap-2"
      style={{ '--w': isMe ? 'var(--card-hero)' : 'var(--card-opponent)' } as CSSProperties}
    >
      {[0, 1].map((index) => (
        <Card
          // A new hand is new cards, so they are dealt again rather than reused.
          key={`${view.handNumber}-${index}`}
          card={player.cards?.[index] ?? null}
          dealDelay={(index * 2 + seatOrder) * 110}
          dealFrom={dealFrom}
          // Your own cards turn over once they land. The opponent's turn only
          // at a showdown, a beat after the room dims.
          flipDelay={isMe ? 520 + index * 120 : 350 + index * 140}
          liftable={isMe}
        />
      ))}
    </div>
  )

  const bet = (
    <div className="flex h-6 items-center justify-center gap-2">
      <span data-anchor={`bet-${player.seat}`} className="grid h-6 w-6 place-items-center">
        {player.committed > 0 && <ChipStack amount={player.committed} bigBlind={view.bigBlind} size={20} />}
      </span>
      {player.committed > 0 && (
        <span className="figure text-sm font-semibold text-ivory">{player.committed.toLocaleString('en-US')}</span>
      )}
      {action && !result && <span className="label text-gold!">{action.split(' ')[0]}</span>}
      {hand && (
        <span className={`label rise-in ${winner ? 'text-gold-bright!' : ''}`} style={{ '--rise-delay': '800ms' } as CSSProperties}>
          {hand}
        </span>
      )}
    </div>
  )

  const plate = (
    <div className="plate glass glass--strong flex items-center gap-2.5 rounded-full py-1.5 pr-4 pl-1.5">
      <span
        data-anchor={`stack-${player.seat}`}
        className={`grid h-8 w-8 place-items-center rounded-full text-xs font-bold ${
          isMe ? 'bg-gold/20 text-gold-bright ring-1 ring-gold/40' : 'bg-black/30 ring-1 ring-white/10'
        }`}
      >
        {isMe ? (
          'Y'
        ) : (
          <img src="/logo-192.png" alt="" width={32} height={32} className="h-8 w-8 rounded-full" />
        )}
      </span>

      <span className="leading-tight">
        <span className="label block">{player.name}</span>
        <span className="block text-base font-semibold text-ivory">
          {/* The winner's stack counts up only once the chips reach it. */}
          <AnimatedNumber value={player.stack} delay={result ? TIMING.PAYOUT_AT + 300 : 0} />
        </span>
      </span>

      {view.buttonSeat === player.seat && (
        <span className="dealer-button" title="Dealer button" aria-label="Dealer button">
          D
        </span>
      )}
      {player.status === 'all_in' && <span className="label text-gold-bright!">All-in</span>}
      {folded && <span className="label">Folded</span>}
    </div>
  )

  return (
    <section
      aria-label={`${player.name}'s seat`}
      className="seat flex flex-col items-center gap-1.5"
      data-me={isMe}
      data-acting={acting}
      data-winner={winner}
      data-folded={folded}
    >
      {/* On a short screen the plate sits beside the cards instead of stacking. */}
      {isMe && bet}
      <div className="flex flex-col items-center gap-1.5 short:flex-row short:gap-3">
        {isMe ? (
          <>
            {cards}
            {plate}
          </>
        ) : (
          <>
            {plate}
            {cards}
          </>
        )}
      </div>
      {!isMe && bet}
    </section>
  )
}
