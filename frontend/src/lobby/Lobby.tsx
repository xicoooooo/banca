import { FriendsCard } from '../friends/FriendsCard'
import { Missions } from './Missions'
import { JoinByCode } from './JoinByCode'
import { OpenTablePanel } from '../casino/OpenTablePanel'
import { InstallCard } from '../casino/AppNotes'
import { useEffect, useState } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { useDashboard } from '../player/useDashboard'
import { Crest } from '../leagues/Crest'
import { fetchLeague } from '../player/api'
import type { League } from '../player/types'
import { chips, signed, toneOf } from '../profile/format'
import { DailyReward } from './DailyReward'

export type Game = 'poker' | 'blackjack' | 'roulette'

const GAMES: { id: Game; name: string; line: string; detail: string }[] = [
  {
    id: 'poker',
    name: "Texas Hold'em",
    line: 'Banca has a seat at every table',
    detail: 'Play it heads up, or with other players at the table. It works out its odds with tools, and shows you how after every hand.',
  },
  {
    id: 'blackjack',
    name: 'Blackjack',
    line: 'One dealer for the whole table',
    detail: 'Sit down with other players or take a table to yourself. Banca is beside you if you ask what it would do.',
  },
  {
    id: 'roulette',
    name: 'Roulette',
    line: 'One wheel for the whole room',
    detail: 'A European wheel with a single zero. Join a room with other players, or take a table to yourself.',
  },
]

const ORDINAL = ['', '1st', '2nd', '3rd']
const ordinal = (position: number) => ORDINAL[position] ?? `${position}th`

/** Where a visit starts: who is playing, what they have, and the games on offer. */
type LobbyProps = { onChoose: (game: Game) => void; onProfile: () => void; onLeagues: () => void; onFriends: () => void }

export function Lobby({ onChoose, onProfile, onLeagues, onFriends }: LobbyProps) {
  const { dashboard, refresh } = useDashboard()
  const [league, setLeague] = useState<League | null>(null)

  // The league is asked for once the player is known, so a first visit makes one guest and not two.
  const known = dashboard !== null
  useEffect(() => {
    if (!known) return
    let disposed = false
    fetchLeague().then(
      (loaded) => {
        if (!disposed) setLeague(loaded)
      },
      // The lobby is complete without it.
      () => undefined,
    )
    return () => {
      disposed = true
    }
  }, [known])

  const mine = league?.rows.find((row) => row.you)
  const [settingUp, setSettingUp] = useState(false)

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

        {league && (
          <button type="button" onClick={onLeagues} className="league-card rise-in" style={{ ['--rise-delay' as string]: '40ms' }} aria-label="Open the leagues">
            <Crest league={league.tierName} size={34} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-base font-semibold tracking-tight">{league.signedIn ? `${league.tierName} League` : 'Weekly leagues'}</span>
              <span className="label block tracking-[0.12em]!">
                {!league.signedIn
                  ? 'Sign in to take your place'
                  : mine && mine.position > 0
                    ? `${ordinal(mine.position)} this week${mine.zone === 'promotion' ? ' · going up' : mine.zone === 'demotion' ? ' · going down' : ''}`
                    : 'Not played yet this week'}
              </span>
            </span>
            {mine && mine.position > 0 && (
              <span className="figure text-base font-semibold" data-tone={toneOf(mine.net)}>
                {signed(mine.net)}
              </span>
            )}
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="flex-none text-muted">
              <path d="M9 5l7 7-7 7" />
            </svg>
          </button>
        )}

        <FriendsCard onOpen={onFriends} />

        {dashboard && (
          <DailyReward rewards={dashboard.rewards} balance={dashboard.bankroll.balance} smallestBet={10} onClaimed={refresh} />
        )}
        {dashboard && <Missions missions={dashboard.missions} onClaimed={refresh} />}

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

        <button type="button" onClick={() => setSettingUp(true)} className="game-tile game-tile--friends rise-in" style={{ ['--rise-delay' as string]: '270ms' }}>
          <span className="label text-gold!">With friends</span>
          <span className="text-2xl font-semibold tracking-tight">Open a private table</span>
          <span className="text-sm leading-relaxed text-muted">Choose the game and the seats, send the link, and start when everyone is there.</span>
        </button>

        <JoinByCode />

        <InstallCard />

        <p className="label pt-2 text-center leading-relaxed">
          Chips are free and have no value
          <br />
          Nothing here can be bought or cashed out
          <br />
          <a href="/privacy.html" className="underline decoration-white/25 underline-offset-4 transition hover:text-ivory">
            Privacy
          </a>
        </p>
      </div>
      {settingUp && <OpenTablePanel onClose={() => setSettingUp(false)} />}
    </CasinoShell>
  )
}
