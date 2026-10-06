import type { TableListing } from '../casino/room'
import { TablePicker } from '../casino/TablePicker'

type BlackjackTableListing = TableListing & { seats: number }

/** Where to play blackjack: at a table with other players, or one to oneself. */
export function BlackjackPicker({ onLeave, onChoose }: { onLeave: () => void; onChoose: (table: string) => void }) {
  return (
    <TablePicker<BlackjackTableListing>
      game="Blackjack"
      kicker="One dealer for the whole table"
      heading="Choose a table"
      listedAt="/blackjack/tables"
      occupancy={(table) => ({
        text: table.players >= table.seats ? 'Full' : `${table.players} of ${table.seats} seats`,
        busy: table.players > 0,
      })}
      detail={(table) => (
        <span className="label pt-1">
          {table.players === 0 ? 'Waiting for someone to sit down' : table.players >= table.seats ? 'No seat free just now' : 'Bets 10 to 500'}
        </span>
      )}
      alone="Just you and the dealer, at your own pace, with Banca to ask."
      footnote={
        <>
          At a shared table everyone faces the same dealer
          <br />
          and plays in turn, against the clock
        </>
      }
      onLeave={onLeave}
      onChoose={onChoose}
    />
  )
}
