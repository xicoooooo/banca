import { useEffect, useState, type ReactNode } from 'react'
import { CasinoShell } from './CasinoShell'
import { Header } from './Header'
import type { TableListing } from './room'
import type { GameId } from '../player/types'
import { OpenTablePanel } from './OpenTablePanel'
import { httpUrl } from './server'

// Often enough that a table filling up is seen, seldom enough to cost nothing.
const REFRESH_EVERY_MS = 5_000

type TablePickerProps<Listing extends TableListing> = {
  /** The game's name, for the header. */
  game: string
  /** Which game it is, for opening a private table at it. */
  gameId: GameId
  kicker: string
  heading: string
  /** Where the server lists this game's shared tables. */
  listedAt: string
  /** How full a table is, in a word or two. */
  occupancy: (table: Listing) => { text: string; busy: boolean }
  /** Anything more to show about a table, under its name. */
  detail: (table: Listing) => ReactNode
  /** What is said for the table to oneself. */
  alone: string
  footnote: ReactNode
  onLeave: () => void
  /** Opens a shared table by its id, or 'solo' for the table to oneself. */
  onChoose: (table: string) => void
}

/**
 * Where to play a game: at one of its shared tables, at a private one opened
 * for friends, or at a table to oneself. The same choice at every game.
 */
export function TablePicker<Listing extends TableListing>(props: TablePickerProps<Listing>) {
  const { game, gameId, kicker, heading, listedAt, occupancy, detail, alone, footnote, onLeave, onChoose } = props
  const [tables, setTables] = useState<Listing[] | null>(null)
  const [settingUp, setSettingUp] = useState(false)

  useEffect(() => {
    let disposed = false
    const load = () =>
      fetch(httpUrl(listedAt))
        .then((response) => response.json() as Promise<Listing[]>)
        .then((list) => {
          if (!disposed) setTables(list)
        })
        // A table alone is still on offer if the shared ones cannot be listed.
        .catch(() => undefined)
    void load()
    const timer = setInterval(load, REFRESH_EVERY_MS)
    return () => {
      disposed = true
      clearInterval(timer)
    }
  }, [listedAt])

  return (
    <CasinoShell>
      <Header detail={game} onLeave={onLeave} />

      <div className="m-auto flex w-full max-w-md flex-col gap-3 py-6">
        <div className="pb-2 text-center">
          <p className="label text-gold!">{kicker}</p>
          <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">{heading}</h1>
        </div>

        {(tables ?? []).map((table, index) => {
          const seats = occupancy(table)
          return (
            <button
              key={table.id}
              type="button"
              onClick={() => onChoose(table.id)}
              className="game-tile rise-in"
              style={{ ['--rise-delay' as string]: `${index * 70}ms` }}
            >
              <span className="flex items-baseline justify-between gap-3">
                <span className="text-xl font-semibold tracking-tight">{table.name}</span>
                <span className={`label ${seats.busy ? 'text-gold-bright!' : ''}`}>{seats.text}</span>
              </span>
              {detail(table)}
            </button>
          )
        })}

        <button type="button" onClick={() => setSettingUp(true)} className="game-tile rise-in" style={{ ['--rise-delay' as string]: '240ms' }}>
          <span className="label text-gold!">With friends</span>
          <span className="text-xl font-semibold tracking-tight">Open a private table</span>
          <span className="text-sm leading-relaxed text-muted">
            A table on no list, played at your pace. Send the link, and only the people you invite can sit down with you.
          </span>
        </button>

        <button type="button" onClick={() => onChoose('solo')} className="game-tile rise-in" style={{ ['--rise-delay' as string]: '310ms' }}>
          <span className="label text-gold!">A table to yourself</span>
          <span className="text-xl font-semibold tracking-tight">Play alone</span>
          <span className="text-sm leading-relaxed text-muted">{alone}</span>
        </button>

        <p className="label pt-2 text-center leading-relaxed">{footnote}</p>
      </div>

      {settingUp && <OpenTablePanel game={gameId} onClose={() => setSettingUp(false)} />}
    </CasinoShell>
  )
}
