import type { TableListing } from '../casino/room'
import { TablePicker } from '../casino/TablePicker'

type PokerTableListing = TableListing & { seats: number }

/** Where to play Hold'em: at a table with other players and Banca, or heads up against Banca alone. */
export function PokerPicker({ onLeave, onChoose }: { onLeave: () => void; onChoose: (table: string) => void }) {
  return (
    <TablePicker<PokerTableListing>
      game="Texas Hold'em"
      gameId="poker"
      kicker="Banca has a seat at every table"
      heading="Choose a table"
      listedAt="/poker/tables"
      occupancy={(table) => ({
        text: table.players >= table.seats ? 'Full' : `${table.players} of ${table.seats} seats`,
        busy: table.players > 0,
      })}
      detail={(table) => (
        <span className="label pt-1">
          {table.players === 0 ? 'Banca is waiting for someone to play' : table.players >= table.seats ? 'No seat free just now' : 'Blinds 10 / 20'}
        </span>
      )}
      alone="Heads up against Banca, at your own pace, with its reasoning after every hand."
      footnote={
        <>
          At a shared table hands follow one another
          <br />
          and every decision is against the clock
        </>
      }
      onLeave={onLeave}
      onChoose={onChoose}
    />
  )
}
