import { useState } from 'react'
import { Refused, openTable, type TableOptions } from '../player/api'
import type { GameId } from '../player/types'
import { sound } from './sound'
import { useDialog } from './useDialog'

/** What each game allows at a private table, and where the server opens one. */
const GAMES: Record<GameId, { name: string; openAt: string; seats: [number, number]; usual: number; blurb: string }> = {
  poker: { name: "Texas Hold'em", openAt: '/poker/tables', seats: [2, 6], usual: 6, blurb: 'Hands follow one another once you start the game.' },
  blackjack: { name: 'Blackjack', openAt: '/blackjack/tables', seats: [2, 5], usual: 5, blurb: 'Cards are dealt when everyone has bet, or when you say.' },
  roulette: { name: 'Roulette', openAt: '/roulette/rooms', seats: [2, 8], usual: 8, blurb: 'The wheel turns when you spin it, not on a clock.' },
}

const ORDER: GameId[] = ['poker', 'blackjack', 'roulette']

type OpenTablePanelProps = {
  /** The game to open a table at, when the player has already chosen one. Left out, they choose here. */
  game?: GameId
  onClose: () => void
}

/**
 * Where a player sets up a private table before opening it: which game, how
 * many seats, and how it is to be played. Opening it takes them straight
 * there, where the link to send is waiting.
 */
export function OpenTablePanel({ game: fixed, onClose }: OpenTablePanelProps) {
  const closeButton = useDialog(onClose)
  const [game, setGame] = useState<GameId>(fixed ?? 'poker')
  const [seats, setSeats] = useState(GAMES[fixed ?? 'poker'].usual)
  const [banca, setBanca] = useState(true)
  const [longTurns, setLongTurns] = useState(false)
  const [opening, setOpening] = useState(false)
  const [refused, setRefused] = useState<string | null>(null)

  const rules = GAMES[game]
  const [fewest, most] = rules.seats

  const choose = (next: GameId) => {
    sound.click()
    setGame(next)
    setSeats(GAMES[next].usual)
  }

  const open = async () => {
    if (opening) return
    sound.click()
    setOpening(true)
    setRefused(null)
    const options: TableOptions = { seats, banca: game === 'poker' ? banca : true, turns: longTurns && game !== 'roulette' ? 'long' : 'normal' }
    try {
      const table = await openTable(rules.openAt, options)
      window.location.hash = `/${game}/${table.id}`
      onClose()
    } catch (problem) {
      setRefused(problem instanceof Refused ? problem.message : 'The table could not be opened. Try again in a moment.')
      setOpening(false)
    }
  }

  // At poker Banca takes one of the seats, so friends have one fewer.
  const forFriends = game === 'poker' && banca ? seats - 1 : seats

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label="Open a private table" onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">With friends</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">Open a private table</h2>
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="flex flex-col gap-5 overflow-y-auto px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          {!fixed && (
            <fieldset className="setup">
              <legend className="label">Game</legend>
              <div className="setup__choices">
                {ORDER.map((id) => (
                  <button key={id} type="button" className="btn btn--quiet" data-selected={game === id} aria-pressed={game === id} onClick={() => choose(id)}>
                    {GAMES[id].name}
                  </button>
                ))}
              </div>
            </fieldset>
          )}

          <fieldset className="setup">
            <legend className="label">Seats</legend>
            <div className="stepper">
              <button type="button" className="btn btn--quiet" onClick={() => setSeats((count) => Math.max(fewest, count - 1))} disabled={seats <= fewest} aria-label="One seat fewer">
                −
              </button>
              <p className="stepper__value" aria-live="polite">
                <span className="figure text-3xl font-semibold text-ivory">{seats}</span>
                <span className="label block pt-0.5 tracking-[0.12em]!">
                  {game === 'poker' && banca ? `You, Banca and ${forFriends - 1 === 1 ? '1 friend' : `${forFriends - 1} friends`}` : `You and ${seats - 1 === 1 ? '1 friend' : `${seats - 1} friends`}`}
                </span>
              </p>
              <button type="button" className="btn btn--quiet" onClick={() => setSeats((count) => Math.min(most, count + 1))} disabled={seats >= most} aria-label="One seat more">
                +
              </button>
            </div>
          </fieldset>

          {game === 'poker' && (
            <fieldset className="setup">
              <legend className="label">Banca</legend>
              <div className="setup__choices">
                <button type="button" className="btn btn--quiet" data-selected={banca} aria-pressed={banca} onClick={() => setBanca(true)}>
                  Banca plays too
                </button>
                <button type="button" className="btn btn--quiet" data-selected={!banca} aria-pressed={!banca} onClick={() => setBanca(false)}>
                  Friends only
                </button>
              </div>
            </fieldset>
          )}

          {game !== 'roulette' && (
            <fieldset className="setup">
              <legend className="label">Time for a decision</legend>
              <div className="setup__choices">
                <button type="button" className="btn btn--quiet" data-selected={!longTurns} aria-pressed={!longTurns} onClick={() => setLongTurns(false)}>
                  Normal
                </button>
                <button type="button" className="btn btn--quiet" data-selected={longTurns} aria-pressed={longTurns} onClick={() => setLongTurns(true)}>
                  Relaxed
                </button>
              </div>
              <p className="pt-2 text-xs leading-relaxed text-muted">
                {longTurns ? 'Three times as long over each decision. Good for talking it through.' : 'The same pace as the open tables.'}
              </p>
            </fieldset>
          )}

          <div>
            <button type="button" className="btn btn--raise w-full" onClick={open} disabled={opening} data-pending={opening}>
              {opening ? 'Opening your table' : 'Open the table'}
            </button>
            {refused && (
              <p role="alert" className="pt-2.5 text-center text-sm text-gold-bright">
                {refused}
              </p>
            )}
            <p className="pt-3 text-center text-xs leading-relaxed text-muted">
              {rules.blurb} The table is on no list: only people you send the link to can find it.
            </p>
          </div>
        </div>
      </section>
    </div>
  )
}
