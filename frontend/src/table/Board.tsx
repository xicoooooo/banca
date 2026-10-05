import type { CSSProperties } from 'react'
import { Card, CardSlot } from '../casino/Card'
import { useCountBefore } from '../casino/useCountBefore'
import type { TableView } from './types'
import { TIMING } from './usePresentation'

/** The five community cards, dealt into their places a street at a time. */
export function Board({ view }: { view: TableView }) {
  const alreadyDown = useCountBefore(view.board.length, view.handNumber)

  return (
    <section aria-label="Board">
      <div className="board-row flex justify-center gap-1.5 sm:gap-2.5" style={{ '--w': 'var(--card-board)' } as CSSProperties}>
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
              flipDelay={dealAt + 340}
              seed={view.handNumber * 10 + 5 + index}
            />
          )
        })}
      </div>
    </section>
  )
}
