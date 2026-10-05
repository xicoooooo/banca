import { useState, type CSSProperties } from 'react'
import { Card, CardSlot } from '../casino/Card'
import type { TableView } from './types'
import { TIMING } from './usePresentation'

/**
 * How many cards were already on the board before the latest ones arrived, so
 * the new ones can be dealt in turn. Kept as state adjusted during render,
 * which is how React asks for a value that depends on the previous one.
 */
function useAlreadyDown(count: number, handNumber: number): number {
  const [seen, setSeen] = useState({ count, handNumber, before: 0 })

  if (seen.count !== count || seen.handNumber !== handNumber) {
    const before = seen.handNumber === handNumber ? seen.count : 0
    setSeen({ count, handNumber, before })
    return before
  }
  return seen.before
}

/** The five community cards, dealt into their places a street at a time. */
export function Board({ view }: { view: TableView }) {
  const alreadyDown = useAlreadyDown(view.board.length, view.handNumber)

  return (
    <section aria-label="Board">
      <div className="flex justify-center gap-1.5 sm:gap-2.5" style={{ '--w': 'var(--card-board)' } as CSSProperties}>
        {[0, 1, 2, 3, 4].map((index) => {
          const card = view.board[index]
          if (!card) return <CardSlot key={index} />

          // Cards that arrive together are dealt one after another, after the
          // bets have been swept in.
          const order = Math.max(0, index - alreadyDown)
          const dealAt = TIMING.BOARD_AT + order * TIMING.BOARD_STAGGER

          return (
            <Card
              key={`${view.handNumber}-${card}`}
              card={card}
              dealDelay={dealAt}
              dealFrom={{ x: `${(2 - index) * 40}px`, y: '-20vh' }}
              flipDelay={dealAt + 300}
            />
          )
        })}
      </div>
    </section>
  )
}
