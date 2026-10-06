import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { useDashboard } from '../player/useDashboard'
import { chips } from '../profile/format'

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

/** Where a visit starts: who is playing, what they have, and the games on offer. */
export function Lobby({ onChoose, onProfile }: { onChoose: (game: Game) => void; onProfile: () => void }) {
  const { dashboard } = useDashboard()

  return (
    <CasinoShell>
      <Header detail="Play money only" />

      <div className="m-auto flex w-full max-w-md flex-col gap-4 py-6">
        <div className="pb-2 text-center">
          <p className="label text-gold!">The house is open</p>
          <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">Choose your table</h1>
        </div>

        {/* Left out until the server answers; the tables can be chosen meanwhile. */}
        {dashboard && (
          <button type="button" onClick={onProfile} className="player-card rise-in" aria-label="Open your profile">
            <span aria-hidden className="monogram monogram--small">
              {dashboard.player.name.trim().charAt(0).toUpperCase()}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-base font-semibold tracking-tight">{dashboard.player.name}</span>
              <span className="label block tracking-[0.12em]!">
                Level {dashboard.player.level} · {dashboard.player.title}
              </span>
            </span>
            <span className="text-right">
              <span className="figure block text-base font-semibold text-gold-bright">{chips(dashboard.bankroll.balance)}</span>
              <span className="label block tracking-[0.12em]!">chips</span>
            </span>
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="flex-none text-muted">
              <path d="M9 5l7 7-7 7" />
            </svg>
          </button>
        )}

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
