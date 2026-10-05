import { useEffect, useState, type CSSProperties } from 'react'
import { variance } from './motion'

const SUITS: Record<string, { symbol: string; name: string; red: boolean }> = {
  s: { symbol: '♠', name: 'spades', red: false },
  h: { symbol: '♥', name: 'hearts', red: true },
  d: { symbol: '♦', name: 'diamonds', red: true },
  c: { symbol: '♣', name: 'clubs', red: false },
}

const RANKS: Record<string, { short: string; name: string }> = {
  T: { short: '10', name: '10' },
  J: { short: 'J', name: 'Jack' },
  Q: { short: 'Q', name: 'Queen' },
  K: { short: 'K', name: 'King' },
  A: { short: 'A', name: 'Ace' },
}

type CardProps = {
  /** Two characters such as "As", or null for a card whose face is not known. */
  card: string | null
  /** Milliseconds before the card is dealt in. Leave out for a card that is simply there. */
  dealDelay?: number
  /** Where the deal comes from, as CSS lengths relative to where the card lands. */
  dealFrom?: { x: string; y: string }
  /** Milliseconds after a face becomes known before the card turns over. */
  flipDelay?: number
  liftable?: boolean
  /** Any number that is this card's own. It decides the small ways it differs from its neighbours. */
  seed?: number
}

/**
 * A playing card with a real front and back. It is dealt face down and turned
 * over, never swapped: when its face becomes known the card flips, which is
 * what makes a reveal at showdown feel like one.
 */
export function Card({ card, dealDelay, dealFrom, flipDelay = 0, liftable = false, seed = 0 }: CardProps) {
  const [faceUp, setFaceUp] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setFaceUp(card !== null), card !== null ? flipDelay : 0)
    return () => clearTimeout(timer)
  }, [card, flipDelay])

  const rank = card ? (RANKS[card[0]] ?? { short: card[0], name: card[0] }) : null
  const suit = card ? SUITS[card[1]] : null

  const classes = ['pcard', dealDelay !== undefined && 'pcard--deal', liftable && 'pcard--liftable']
    .filter(Boolean)
    .join(' ')

  return (
    <div
      role="img"
      aria-label={rank && suit ? `${rank.name} of ${suit.name}` : 'Face-down card'}
      className={classes}
      data-face={faceUp ? 'up' : 'down'}
      style={
        {
          // Each card leaves the dealer's hand a little differently and comes
          // to rest a fraction off square, the same way every time it is drawn.
          '--deal-delay': `${Math.max(0, (dealDelay ?? 0) + variance(seed + 1, 22))}ms`,
          '--deal-x': dealFrom?.x,
          '--deal-y': dealFrom?.y,
          '--deal-rot': `${-14 + variance(seed + 2, 9)}deg`,
          '--rest-rot': `${variance(seed + 3, 1.3)}deg`,
        } as CSSProperties
      }
    >
      <div className="pcard__body">
        <div className="pcard__face pcard__front" data-red={suit?.red ?? false}>
          {rank && suit && (
            <>
              <span className="pcard__index">
                {rank.short}
                <small>{suit.symbol}</small>
              </span>
              <span className="pcard__pip">{suit.symbol}</span>
              <span className="pcard__index pcard__index--flipped">
                {rank.short}
                <small>{suit.symbol}</small>
              </span>
            </>
          )}
        </div>
        <div className="pcard__face pcard__back">B</div>
      </div>
    </div>
  )
}

export function CardSlot() {
  return <div aria-hidden className="card-slot" />
}
