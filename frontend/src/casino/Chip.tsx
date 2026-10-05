import type { CSSProperties } from 'react'
import { CHIP_CLASS, chipsFor } from './chips'

/** A small pile standing for an amount: taller and richer as the amount grows. */
export function ChipStack({ amount, bigBlind, size = 22 }: { amount: number; bigBlind: number; size?: number }) {
  return (
    <span aria-hidden className="chip-stack" style={{ '--chip': `${size}px` } as CSSProperties}>
      {chipsFor(amount, bigBlind).map((color, index) => (
        <span
          key={index}
          className={CHIP_CLASS[color]}
          style={{ transform: `translateY(${-index * 3}px) rotate(${index * 23}deg)` }}
        />
      ))}
    </span>
  )
}
