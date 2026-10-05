import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'

export type Game = 'poker' | 'blackjack'

const GAMES: { id: Game; name: string; line: string; detail: string }[] = [
  {
    id: 'poker',
    name: "Texas Hold'em",
    line: 'Heads up against Banca',
    detail: 'An opponent that works out its odds with tools, and shows you how after every hand.',
  },
  {
    id: 'blackjack',
    name: 'Blackjack',
    line: 'You against the house',
    detail: 'Six decks, blackjack pays 3 to 2, and the dealer stands on 17.',
  },
]

/** Where a visit starts: the games on offer, and nothing else. */
export function Lobby({ onChoose }: { onChoose: (game: Game) => void }) {
  return (
    <CasinoShell>
      <Header detail="Play money only" />

      <div className="m-auto flex w-full max-w-md flex-col gap-4 py-6">
        <div className="pb-2 text-center">
          <p className="label text-gold!">The house is open</p>
          <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">Choose your table</h1>
        </div>

        {GAMES.map((game, index) => (
          <button
            key={game.id}
            type="button"
            onClick={() => onChoose(game.id)}
            className="game-tile rise-in"
            style={{ ['--rise-delay' as string]: `${index * 90}ms` }}
          >
            <span className="label text-gold!">{game.line}</span>
            <span className="text-2xl font-semibold tracking-tight">{game.name}</span>
            <span className="text-sm leading-relaxed text-muted">{game.detail}</span>
          </button>
        ))}

        <p className="label pt-2 text-center leading-relaxed">
          Chips are free and have no value
          <br />
          Nothing here can be bought or cashed out
        </p>
      </div>
    </CasinoShell>
  )
}
