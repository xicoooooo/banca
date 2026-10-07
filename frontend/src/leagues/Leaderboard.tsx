import { useEffect, useState, type ReactNode } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { useCountdown } from '../casino/useChipNotices'
import { fetchLeague, fetchTop } from '../player/api'
import type { GameId, League, LeagueRow, TopList } from '../player/types'
import { GAME_NAMES, chips, signed, toneOf } from '../profile/format'
import { Crest } from './Crest'

// Free hosting takes up to a minute to wake the backend, so a first request that fails is tried again.
const RETRY_EVERY_MS = 3_000
const MAX_ATTEMPTS = 20

const ORDINAL = ['', '1st', '2nd', '3rd']
const ordinal = (position: number) => ORDINAL[position] ?? `${position}th`

function Panel({ title, aside, delay = 0, children }: { title: string; aside?: ReactNode; delay?: number; children: ReactNode }) {
  return (
    <section className="panel rise-in" style={{ ['--rise-delay' as string]: `${delay * 60}ms` }}>
      <div className="flex items-baseline justify-between gap-3 pb-4">
        <h2 className="label text-gold!">{title}</h2>
        {aside && <span className="label tracking-[0.12em]!">{aside}</span>}
      </div>
      {children}
    </section>
  )
}

/**
 * The leagues: where the player stands this week among those in their league,
 * how last week went, and who is winning most. Everything here is worked out
 * by the server; this only draws it.
 */
type LeaderboardProps = {
  onLeave: () => void
  onSignIn: () => void
  onPlay: () => void
  /** Opens another player's page, by their id. */
  onPlayer: (id: string) => void
}

export function Leaderboard({ onLeave, onSignIn, onPlay, onPlayer }: LeaderboardProps) {
  const [league, setLeague] = useState<League | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let disposed = false
    let retry: ReturnType<typeof setTimeout> | undefined
    let attempts = 0
    const load = () => {
      attempts++
      fetchLeague().then(
        (loaded) => {
          if (!disposed) setLeague(loaded)
        },
        () => {
          if (disposed) return
          if (attempts < MAX_ATTEMPTS) retry = setTimeout(load, RETRY_EVERY_MS)
          else setFailed(true)
        },
      )
    }
    load()
    return () => {
      disposed = true
      clearTimeout(retry)
    }
  }, [])

  return (
    <CasinoShell>
      <Header detail="Leagues" onLeave={onLeave} />

      {failed ? (
        <Loading failed message="Could not reach the house. Try again in a minute." />
      ) : !league ? (
        <Loading message="Fetching the standings" />
      ) : (
        <div className="flex flex-col gap-4 py-4 sm:gap-5 sm:py-6">
          <Hero league={league} onSignIn={onSignIn} />
          <Standings league={league} onPlay={onPlay} onPlayer={onPlayer} />
          <Winners onPlayer={onPlayer} />
          <HowItWorks league={league} />
        </div>
      )}
    </CasinoShell>
  )
}

/* -------------------------------------------------------------------- hero */

function Hero({ league, onSignIn }: { league: League; onSignIn: () => void }) {
  const left = useCountdown(league.endsAt)
  const last = league.lastWeek

  return (
    <section className="panel league-hero rise-in" data-league={league.tierName}>
      <div className="flex items-center gap-4">
        <Crest league={league.tierName} size={64} />
        <div className="min-w-0 flex-1">
          <p className="label text-gold!">{league.signedIn ? 'Your league this week' : 'This week'}</p>
          <h1 className="pt-1 text-3xl leading-tight font-semibold tracking-tight text-ivory">{league.tierName} League</h1>
          <p className="label pt-1.5 tracking-[0.12em]!">
            Ends in {left} · {league.players} {league.players === 1 ? 'player' : 'players'}
          </p>
        </div>
      </div>

      {/* The ladder: every league there is, with the player's own picked out. */}
      <ol className="league-ladder" aria-label="The leagues, lowest first">
        {league.tiers.map((name, index) => (
          <li key={name} data-current={index === league.tier} aria-current={index === league.tier ? 'step' : undefined}>
            <Crest league={name} size={26} dim={index !== league.tier} />
            <span>{name}</span>
          </li>
        ))}
      </ol>

      {last && (
        <p className="league-last" data-outcome={last.outcome}>
          <span className="label tracking-[0.12em]!">Last week</span>
          <span className="text-sm text-ivory">
            {last.position > 0 ? `${ordinal(last.position)} in ${last.tier}, ${signed(last.net)}` : `You did not play in ${last.tier}`}
            {last.outcome === 'promoted' && ' · promoted'}
            {last.outcome === 'demoted' && ' · moved down a league'}
            {last.prize > 0 && ` · ${chips(last.prize)} chips in prizes`}
          </span>
        </p>
      )}

      {!league.signedIn && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 pt-5">
          <p className="min-w-52 flex-1 text-sm leading-relaxed text-muted">
            Leagues are for players who have signed in, so a place in one is yours to keep. You are looking at the first of them.
          </p>
          <button type="button" className="btn btn--raise px-5! text-sm" onClick={onSignIn}>
            Sign in to join
          </button>
        </div>
      )}
    </section>
  )
}

/* --------------------------------------------------------------- standings */

const ZONE_NOTE = { promotion: 'Going up', safe: '', demotion: 'Going down' }

function Standings({ league, onPlay, onPlayer }: { league: League; onPlay: () => void; onPlayer: (id: string) => void }) {
  const played = league.rows.filter((row) => row.position > 0)
  const waiting = league.rows.filter((row) => row.position === 0)
  const lastUp = played.findLast((row) => row.zone === 'promotion')
  const firstDown = played.find((row) => row.zone === 'demotion')

  return (
    <Panel title="Standings" aside={`Prizes ${league.rules.prizes.map(chips).join(' · ')}`} delay={1}>
      {played.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-white/10 px-4 py-7 text-center">
          <p className="text-sm leading-relaxed text-muted">Nobody in this league has played yet this week. The top of the table is there for the taking.</p>
          <button type="button" className="btn btn--call mt-4 px-5! text-sm" onClick={onPlay}>
            Find a table
          </button>
        </div>
      ) : (
        <ol className="standings">
          {played.map((row) => (
            <Row
              key={`${row.position}-${row.name}`}
              row={row}
              divider={row === lastUp ? 'up' : row === firstDown ? 'down' : null}
              onOpen={() => onPlayer(row.id)}
              // In a paying place and ahead, but not yet played enough for it to count.
              roundsShort={row.position <= league.rules.promoted && row.net > 0 ? Math.max(0, league.rules.minRounds - row.rounds) : 0}
            />
          ))}
        </ol>
      )}

      {waiting.map((row) => (
        <p key={row.name} className="pt-3 text-sm leading-relaxed text-muted">
          {row.you
            ? `You have not played this week. Your first round puts you on the table${league.tier > 0 ? ', and a week without playing costs a league' : ''}.`
            : ''}
        </p>
      ))}
    </Panel>
  )
}

function Row({ row, divider, roundsShort, onOpen }: { row: LeagueRow; divider: 'up' | 'down' | null; roundsShort: number; onOpen: () => void }) {
  return (
    <>
      {divider === 'down' && (
        <li className="standings__line" data-zone="demotion" aria-hidden>
          <span>Relegation below</span>
        </li>
      )}
      <li className="standings__row" data-you={row.you} data-zone={row.zone}>
        <span className="standings__position figure">{row.position}</span>
        <span className="min-w-0 flex-1">
          <button type="button" className="standings__name" onClick={onOpen} aria-label={`Open ${row.name}'s page`}>
            <span className="truncate">{row.name}</span>
            {row.you && <span className="label pl-2 text-gold-bright!">You</span>}
          </button>
          <span className="label block tracking-[0.1em]!">
            {row.rounds} {row.rounds === 1 ? 'round' : 'rounds'}
            {roundsShort > 0 && (
              <span className="text-gold!">
                {' · '}
                {roundsShort} more {roundsShort === 1 ? 'round' : 'rounds'} to qualify
              </span>
            )}
            {ZONE_NOTE[row.zone] && (
              <span className="standings__zone" data-zone={row.zone}>
                {' · '}
                {row.zone === 'promotion' ? '▲' : '▼'} {ZONE_NOTE[row.zone]}
              </span>
            )}
          </span>
        </span>
        <span className="figure text-base font-semibold" data-tone={toneOf(row.net)}>
          {signed(row.net)}
        </span>
      </li>
      {divider === 'up' && (
        <li className="standings__line" data-zone="promotion" aria-hidden>
          <span>Promotion above</span>
        </li>
      )}
    </>
  )
}

/* ----------------------------------------------------------------- winners */

const GAMES: (GameId | null)[] = [null, 'poker', 'blackjack', 'roulette']

function Winners({ onPlayer }: { onPlayer: (id: string) => void }) {
  const [period, setPeriod] = useState<'week' | 'all'>('week')
  const [game, setGame] = useState<GameId | null>(null)
  const [list, setList] = useState<TopList | null>(null)

  useEffect(() => {
    let disposed = false
    fetchTop(period, game).then(
      (loaded) => {
        if (!disposed) setList(loaded)
      },
      // The league above is the main thing; this list can be asked for again.
      () => undefined,
    )
    return () => {
      disposed = true
    }
  }, [period, game])

  const current = list?.period === period && list.game === game ? list : null

  return (
    <Panel title="Biggest winners" delay={2}>
      <div className="flex flex-wrap items-center gap-2 pb-4">
        <div className="segmented" role="group" aria-label="Period">
          <button type="button" aria-pressed={period === 'week'} onClick={() => setPeriod('week')}>
            This week
          </button>
          <button type="button" aria-pressed={period === 'all'} onClick={() => setPeriod('all')}>
            All time
          </button>
        </div>
        <div className="segmented" role="group" aria-label="Game">
          {GAMES.map((option) => (
            <button key={option ?? 'all'} type="button" aria-pressed={game === option} onClick={() => setGame(option)}>
              {option ? GAME_NAMES[option] : 'All games'}
            </button>
          ))}
        </div>
      </div>

      {!current ? (
        <p className="label py-6 text-center">Counting the chips</p>
      ) : current.rows.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-white/10 px-4 py-6 text-center text-sm leading-relaxed text-muted">
          Nobody who has signed in has played {game ? GAME_NAMES[game] : 'at a table'}
          {period === 'week' ? ' this week yet.' : ' yet.'}
        </p>
      ) : (
        <ol className="standings">
          {current.rows.map((row) => (
            <li key={`${row.position}-${row.name}`} className="standings__row" data-you={row.you} data-podium={row.position <= 3}>
              <span className="standings__position figure">{row.position}</span>
              <Crest league={row.league} size={22} />
              <span className="min-w-0 flex-1">
                <button type="button" className="standings__name" onClick={() => onPlayer(row.id)} aria-label={`Open ${row.name}'s page`}>
                  <span className="truncate">{row.name}</span>
                  {row.you && <span className="label pl-2 text-gold-bright!">You</span>}
                </button>
                <span className="label block tracking-[0.1em]!">
                  {row.league} · {row.rounds} {row.rounds === 1 ? 'round' : 'rounds'}
                </span>
              </span>
              <span className="figure text-base font-semibold" data-tone={toneOf(row.net)}>
                {signed(row.net)}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}

/* ------------------------------------------------------------ how it works */

function HowItWorks({ league }: { league: League }) {
  const { rules } = league
  return (
    <Panel title="How the leagues work" delay={3}>
      <ul className="league-rules">
        <li>A week runs from Monday to Monday. You are ranked against the players in your league by what you win at the tables, at all three games together.</li>
        <li>
          The top {rules.promoted} go up a league, as long as they played at least {rules.minRounds} rounds and finished ahead. They are paid a prize too, and prizes are
          bigger in the higher leagues.
        </li>
        <li>
          A week without playing costs a league. In a league where at least {rules.demotionNeeds} players have played, the bottom {rules.demoted} go down as well.
        </li>
        <li>Chips from rewards and prizes do not count towards your standing. Only what the cards and the wheel gave you does.</li>
      </ul>
      <p className="label pt-4 leading-relaxed">Chips are free and have no value</p>
    </Panel>
  )
}
