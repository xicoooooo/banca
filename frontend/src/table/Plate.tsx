import type { ReactNode } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'

type PlateProps = {
  /** The seat number, which names the place chips fly to and from. */
  seat: number
  name: string
  stack: number
  /** A picture, or a letter to stand for one. */
  avatar: ReactNode
  isMe: boolean
  hasButton: boolean
  /** A short word about the player's state, such as "All-in". */
  tag?: { text: string; gold?: boolean }
  /** Milliseconds before a change in the stack is shown, to keep time with chips in flight. */
  stackDelay?: number
}

/**
 * Who is sitting here and what they have. Kept apart from the seat so that a
 * player's identity can grow, to a level or a streak, without the table's
 * layout needing to know.
 */
export function Plate({ seat, name, stack, avatar, isMe, hasButton, tag, stackDelay = 0 }: PlateProps) {
  return (
    <div className="plate glass glass--strong flex items-center gap-2.5 rounded-full py-1.5 pr-4 pl-1.5">
      <span
        data-anchor={`stack-${seat}`}
        className={`grid h-8 w-8 place-items-center overflow-hidden rounded-full text-xs font-bold ${
          isMe ? 'bg-gold/20 text-gold-bright ring-1 ring-gold/40' : 'bg-black/30 ring-1 ring-white/10'
        }`}
      >
        {avatar}
      </span>

      <span className="leading-tight">
        <span className="label block">{name}</span>
        <span className="block text-base font-semibold text-ivory">
          <AnimatedNumber value={stack} delay={stackDelay} />
        </span>
      </span>

      {hasButton && (
        <span className="dealer-button" title="Dealer button" aria-label="Dealer button">
          D
        </span>
      )}
      {tag && <span className={`label ${tag.gold ? 'text-gold-bright!' : ''}`}>{tag.text}</span>}
    </div>
  )
}
