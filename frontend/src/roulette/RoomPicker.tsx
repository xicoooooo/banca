import { TablePicker } from '../casino/TablePicker'
import { colorOf } from './layout'
import type { RoomSummary } from './types'

/** Where to play roulette: one of the shared rooms, or a table to oneself. */
export function RoomPicker({ onLeave, onChoose }: { onLeave: () => void; onChoose: (room: string) => void }) {
  return (
    <TablePicker<RoomSummary>
      game="Roulette"
      gameId="roulette"
      kicker="One wheel for the whole room"
      heading="Choose a room"
      listedAt="/roulette/rooms"
      occupancy={(room) => ({ text: room.players === 0 ? 'Empty' : `${room.players} playing`, busy: room.players > 0 })}
      detail={(room) => (
        <span className="history justify-start! pt-1" aria-label="Recent results, newest first">
          {room.history.length === 0 ? (
            <span className="label">The wheel is waiting for someone</span>
          ) : (
            room.history.map((pocket, at) => (
              <span key={at} data-color={colorOf(pocket)} className="history__pocket figure">
                {pocket}
              </span>
            ))
          )}
        </span>
      )}
      alone="No clock and nobody else. Spin when you are ready."
      footnote={
        <>
          In a room, bets close every half minute
          <br />
          and the wheel turns once for everyone
        </>
      }
      onLeave={onLeave}
      onChoose={onChoose}
    />
  )
}
