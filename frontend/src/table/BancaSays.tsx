import type { CSSProperties } from 'react'

/**
 * Something Banca has to say about the hand just played. It comes a beat
 * after the result, so the table is seen first and the remark second: once
 * the cards have turned at a showdown, and sooner without one.
 */
export function BancaSays({ text, afterShowdown }: { text: string; afterShowdown: boolean }) {
  return (
    <p className="banca-says rise-in" style={{ '--rise-delay': afterShowdown ? '1500ms' : '700ms' } as CSSProperties}>
      <img src="/logo-192.png" alt="" width={18} height={18} />
      <span className="sr-only">Banca says: </span>
      <q>{text}</q>
    </p>
  )
}
