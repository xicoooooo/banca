import { useState, type FormEvent, type ReactNode } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { Refused, renamePlayer } from '../player/api'
import type { Achievement, Dashboard, GameBreakdown } from '../player/types'
import { useDashboard } from '../player/useDashboard'
import { ActivityChart, BankrollChart } from './charts'
import { GAME_NAMES, ago, chips, levelProgress, monthAndYear, percent, signed, toneOf } from './format'

type ProfileProps = {
  onLeave: () => void
  onPlay: (game: 'poker' | 'blackjack') => void
}

/** The player's own page: who they are, what they have, and how they have played. */
export function Profile({ onLeave, onPlay }: ProfileProps) {
  const { status, dashboard, refresh } = useDashboard()

  return (
    <CasinoShell>
      <Header detail="Player profile" onLeave={onLeave} />

      {status === 'failed' ? (
        <Loading failed message="Could not reach the house. Try again in a minute." />
      ) : status === 'loading' ? (
        <Loading message="Fetching your record" />
      ) : (
        <div className="flex flex-col gap-4 py-4 sm:gap-5 sm:py-6">
          <Identity dashboard={dashboard} onRenamed={refresh} />
          <BankrollPanel dashboard={dashboard} />

          {dashboard.totals.rounds === 0 ? (
            <FirstVisit onPlay={onPlay} />
          ) : (
            <>
              <Statistics dashboard={dashboard} />
              <Section title="Last 14 days" delay={3}>
                <ActivityChart activity={dashboard.activity} />
              </Section>
              <div className="grid gap-4 sm:grid-cols-2 sm:gap-5">
                {dashboard.games.map((game) => (
                  <GamePanel key={game.game} game={game} onPlay={() => onPlay(game.game)} />
                ))}
              </div>
            </>
          )}

          <Progression dashboard={dashboard} />
          <Achievements achievements={dashboard.achievements} />
          {dashboard.recent.length > 0 && <Recent recent={dashboard.recent} />}

          <p className="label py-2 text-center leading-relaxed">
            Chips are free and have no value
            <br />
            Every figure here comes from rounds you have played
          </p>
        </div>
      )}
    </CasinoShell>
  )
}

function Section({ title, aside, delay = 0, children }: { title: string; aside?: ReactNode; delay?: number; children: ReactNode }) {
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

/* ---------------------------------------------------------------- identity */

function Identity({ dashboard, onRenamed }: { dashboard: Dashboard; onRenamed: () => void }) {
  const { player } = dashboard
  const [editing, setEditing] = useState(false)

  return (
    <section className="panel rise-in flex items-center gap-4">
      <span aria-hidden className="monogram">
        {player.name.trim().charAt(0).toUpperCase()}
      </span>

      <div className="min-w-0 flex-1">
        {editing ? (
          <RenameForm
            name={player.name}
            onDone={(changed) => {
              setEditing(false)
              if (changed) onRenamed()
            }}
          />
        ) : (
          <>
            <div className="flex items-center gap-2">
              <h1 className="truncate text-2xl font-semibold tracking-tight text-ivory">{player.name}</h1>
              <button
                type="button"
                onClick={() => setEditing(true)}
                aria-label="Change your name"
                className="grid h-8 w-8 flex-none place-items-center rounded-lg text-muted transition hover:bg-white/5 hover:text-ivory"
              >
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4" />
                </svg>
              </button>
            </div>
            <p className="label pt-1 tracking-[0.12em]!">
              Level {player.level} · {player.title} · Since {monthAndYear(player.memberSince)}
            </p>
          </>
        )}
      </div>
    </section>
  )
}

function RenameForm({ name, onDone }: { name: string; onDone: (changed: boolean) => void }) {
  const [value, setValue] = useState(name)
  const [problem, setProblem] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (value.trim() === name) return onDone(false)
    setSaving(true)
    try {
      await renamePlayer(value)
      onDone(true)
    } catch (failure) {
      setProblem(failure instanceof Refused ? failure.message : 'Could not save that just now')
      setSaving(false)
    }
  }

  return (
    <form onSubmit={save} className="flex flex-col gap-2">
      <div className="flex gap-2">
        <input
          autoFocus
          value={value}
          onChange={(event) => setValue(event.target.value)}
          maxLength={20}
          aria-label="Your name"
          aria-invalid={problem !== null}
          className="name-field min-w-0 flex-1"
        />
        <button type="submit" disabled={saving} className="btn btn--quiet px-4! text-sm">
          Save
        </button>
        <button type="button" onClick={() => onDone(false)} className="btn btn--quiet px-3! text-sm" aria-label="Cancel">
          ✕
        </button>
      </div>
      <p role={problem ? 'alert' : undefined} className="label tracking-[0.1em]!">
        {problem ?? '2 to 20 letters, numbers or spaces'}
      </p>
    </form>
  )
}

/* ---------------------------------------------------------------- bankroll */

function BankrollPanel({ dashboard }: { dashboard: Dashboard }) {
  const { bankroll } = dashboard

  return (
    <Section title="Bankroll" delay={1}>
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3 pb-5">
        <p className="text-5xl leading-none font-semibold tracking-tight text-ivory">
          <AnimatedNumber value={bankroll.balance} />
          <span className="label pl-2 align-middle">chips</span>
        </p>
        <dl className="flex gap-6">
          <Fact label="At the tables" value={signed(bankroll.net)} tone={toneOf(bankroll.net)} />
          <Fact label="Highest" value={chips(bankroll.peak)} />
          <Fact label="Granted" value={chips(bankroll.granted)} />
        </dl>
      </div>

      {bankroll.history.length > 1 ? (
        <BankrollChart history={bankroll.history} />
      ) : (
        <Empty>Your bankroll will be drawn here, round by round, once you have played.</Empty>
      )}
    </Section>
  )
}

function Fact({ label, value, tone = 'even' }: { label: string; value: string; tone?: 'gain' | 'loss' | 'even' }) {
  return (
    <div>
      <dt className="label tracking-[0.12em]!">{label}</dt>
      <dd className="figure m-0 pt-1 text-base font-medium" data-tone={tone}>
        {value}
      </dd>
    </div>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="rounded-2xl border border-dashed border-white/10 px-4 py-6 text-center text-sm leading-relaxed text-muted">{children}</p>
}

function FirstVisit({ onPlay }: { onPlay: ProfileProps['onPlay'] }) {
  return (
    <section className="panel rise-in text-center" style={{ ['--rise-delay' as string]: '120ms' }}>
      <h2 className="text-xl font-semibold tracking-tight text-ivory">Nothing on the record yet</h2>
      <p className="mx-auto max-w-sm pt-2 text-sm leading-relaxed text-muted">
        Your statistics, charts and history are built from the rounds you play. Sit down at a table and they will start to
        fill in.
      </p>
      <div className="flex flex-wrap justify-center gap-3 pt-5">
        <button type="button" className="btn btn--call px-5!" onClick={() => onPlay('poker')}>
          Play Hold'em
        </button>
        <button type="button" className="btn btn--quiet px-5!" onClick={() => onPlay('blackjack')}>
          Play blackjack
        </button>
      </div>
    </section>
  )
}

/* -------------------------------------------------------------- statistics */

function Statistics({ dashboard }: { dashboard: Dashboard }) {
  const { totals, streaks } = dashboard
  const streak =
    streaks.currentKind === null ? '—' : `${streaks.current} ${streaks.currentKind === 'win' ? 'won' : 'lost'}`

  return (
    <Section title="Statistics" aside={`${totals.wins} won · ${totals.losses} lost · ${totals.pushes} pushed`} delay={2}>
      <dl className="stats">
        <Stat label="Rounds played" value={chips(totals.rounds)} />
        <Stat label="Win rate" value={totals.winRate === null ? '—' : percent(totals.winRate)} />
        <Stat
          label="Average result"
          value={totals.averageResult === null ? '—' : signed(totals.averageResult)}
          tone={toneOf(Math.round(totals.averageResult ?? 0))}
        />
        <Stat label="Biggest win" value={totals.biggestWin === null ? '—' : signed(totals.biggestWin)} tone={totals.biggestWin ? 'gain' : 'even'} />
        <Stat label="Biggest loss" value={totals.biggestLoss === null ? '—' : signed(totals.biggestLoss)} tone={totals.biggestLoss ? 'loss' : 'even'} />
        <Stat label="Current streak" value={streak} tone={streaks.currentKind === 'win' ? 'gain' : streaks.currentKind === 'loss' ? 'loss' : 'even'} />
        <Stat label="Best winning run" value={String(streaks.bestWin)} />
        <Stat label="Total staked" value={chips(totals.staked)} />
        <Stat label="Biggest pot won" value={totals.biggestPot === null ? '—' : chips(totals.biggestPot)} />
      </dl>
    </Section>
  )
}

function Stat({ label, value, tone = 'even' }: { label: string; value: string; tone?: 'gain' | 'loss' | 'even' }) {
  return (
    <div className="stat">
      <dt className="label tracking-[0.12em]!">{label}</dt>
      <dd className="figure m-0 pt-1.5 text-xl font-semibold tracking-tight" data-tone={tone}>
        {value}
      </dd>
    </div>
  )
}

/* ------------------------------------------------------------------- games */

function GamePanel({ game, onPlay }: { game: GameBreakdown; onPlay: () => void }) {
  const name = GAME_NAMES[game.game] ?? game.game

  if (game.rounds === 0) {
    return (
      <Section title={name} delay={4}>
        <Empty>You have not played this one yet.</Empty>
        <button type="button" className="btn btn--quiet mt-4 w-full text-sm" onClick={onPlay}>
          Take a seat
        </button>
      </Section>
    )
  }

  return (
    <Section title={name} aside={`${chips(game.rounds)} ${game.rounds === 1 ? 'round' : 'rounds'}`} delay={4}>
      <div className="flex items-end justify-between pb-3">
        <p className="figure text-3xl leading-none font-semibold tracking-tight" data-tone={toneOf(game.net)}>
          {signed(game.net)}
        </p>
        <p className="label tracking-[0.12em]!">{game.winRate === null ? '' : `${percent(game.winRate)} won`}</p>
      </div>

      <div
        className="split"
        role="img"
        aria-label={`${game.wins} won, ${game.pushes} pushed, ${game.losses} lost`}
      >
        {game.wins > 0 && <span data-tone="gain" style={{ flexGrow: game.wins }} />}
        {game.pushes > 0 && <span data-tone="even" style={{ flexGrow: game.pushes }} />}
        {game.losses > 0 && <span data-tone="loss" style={{ flexGrow: game.losses }} />}
      </div>
      <p className="label flex justify-between pt-2 tracking-[0.12em]!">
        <span>{game.wins} won</span>
        {game.pushes > 0 && <span>{game.pushes} pushed</span>}
        <span>{game.losses} lost</span>
      </p>

      <h3 className="label pt-5 pb-2">How you play</h3>
      {game.tendencies.length === 0 ? (
        <p className="text-sm leading-relaxed text-muted">
          {game.rounds < 10
            ? `Shown after 10 rounds. ${10 - game.rounds} to go.`
            : 'Not enough of these situations have come up yet.'}
        </p>
      ) : (
        <dl className="flex flex-col gap-2">
          {game.tendencies.map((tendency) => (
            <div key={tendency.label} className="flex items-baseline justify-between gap-3">
              <dt className="min-w-0 text-sm text-ivory/85">
                {tendency.label}
                <span className="label block pt-0.5 tracking-[0.08em]! normal-case">{tendency.basis}</span>
              </dt>
              <dd className="figure m-0 text-base font-semibold text-ivory">{tendency.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </Section>
  )
}

/* ------------------------------------------------------------- progression */

function Progression({ dashboard }: { dashboard: Dashboard }) {
  const { player } = dashboard
  const progress = levelProgress(player.xp, player.levelStart, player.nextLevelAt)

  return (
    <Section title="Progression" aside={`${chips(player.xp)} XP`} delay={5}>
      <div className="flex items-center gap-4">
        <span className="level-badge figure" aria-hidden>
          {player.level}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-3 pb-2">
            <p className="text-lg font-semibold tracking-tight text-ivory">{player.title}</p>
            <p className="label figure tracking-[0.12em]!">
              {chips(player.nextLevelAt - player.xp)} XP to level {player.level + 1}
            </p>
          </div>
          <div
            className="meter"
            role="progressbar"
            aria-label={`Progress to level ${player.level + 1}`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(progress * 100)}
          >
            <span style={{ width: `${progress * 100}%` }} />
          </div>
        </div>
      </div>
      <p className="pt-4 text-sm leading-relaxed text-muted">
        Every round earns 10 XP, and a win 15 more. A blackjack or a showdown won earns a little extra.
      </p>
    </Section>
  )
}

/* ------------------------------------------------------------ achievements */

function Achievements({ achievements }: { achievements: Achievement[] }) {
  const earned = achievements.filter((achievement) => achievement.earned).length

  return (
    <Section title="Achievements" aside={`${earned} of ${achievements.length}`} delay={6}>
      <ul className="m-0 grid list-none gap-2.5 p-0 sm:grid-cols-2">
        {achievements.map((achievement) => (
          <li key={achievement.id} className="award" data-earned={achievement.earned}>
            <span aria-hidden className="award__seal">
              {achievement.earned ? (
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M5 12.5l4.5 4.5L19 7.5" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="5" y="11" width="14" height="9" rx="2" />
                  <path d="M8 11V8a4 4 0 018 0v3" />
                </svg>
              )}
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold text-ivory">
                {achievement.name}
                <span className="sr-only">{achievement.earned ? ', earned' : ', not yet earned'}</span>
              </p>
              <p className="text-xs leading-relaxed text-muted">{achievement.description}</p>
              {!achievement.earned && achievement.target > 1 && (
                <div className="flex items-center gap-2 pt-1.5">
                  <div className="meter meter--thin flex-1" aria-hidden>
                    <span style={{ width: `${Math.min(100, (achievement.progress / achievement.target) * 100)}%` }} />
                  </div>
                  <span className="label figure tracking-[0.08em]!">
                    {chips(achievement.progress)} / {chips(achievement.target)}
                  </span>
                </div>
              )}
            </div>
          </li>
        ))}
      </ul>
    </Section>
  )
}

/* ------------------------------------------------------------------ recent */

/** How many rounds are listed before the player asks for the rest. */
const RECENT_AT_FIRST = 8

function Recent({ recent }: { recent: Dashboard['recent'] }) {
  const [all, setAll] = useState(false)
  const shown = all ? recent : recent.slice(0, RECENT_AT_FIRST)

  return (
    <Section title="Recent rounds" delay={7}>
      <ul className="m-0 flex list-none flex-col p-0">
        {shown.map((round, index) => (
          <li key={`${round.at}-${index}`} className="round">
            <span aria-hidden className="round__mark" data-tone={toneOf(round.net)} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm text-ivory">{round.summary}</p>
              <p className="label pt-0.5 tracking-[0.1em]!">
                {GAME_NAMES[round.game] ?? round.game} · {ago(round.at)}
              </p>
            </div>
            <span className="figure text-sm font-semibold" data-tone={toneOf(round.net)}>
              {signed(round.net)}
            </span>
          </li>
        ))}
      </ul>
      {recent.length > RECENT_AT_FIRST && (
        <button type="button" className="btn btn--quiet mt-4 w-full text-sm" onClick={() => setAll(!all)} aria-expanded={all}>
          {all ? 'Show fewer' : `Show all ${recent.length}`}
        </button>
      )}
    </Section>
  )
}
