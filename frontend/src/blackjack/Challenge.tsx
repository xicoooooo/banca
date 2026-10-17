import { useEffect, useRef, useState } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { InviteButton, TableCode } from '../casino/Invite'
import { Loading } from '../casino/Loading'
import { sound } from '../casino/sound'
import { shownCode } from '../casino/tableCode'
import { Refused, setChallenge } from '../player/api'
import { ordinal } from '../table/places'
import type { ChallengeView } from './types'

const chips = (amount: number) => amount.toLocaleString('en-US')

/** Sets a new challenge, and takes the player straight to it. */
export function StartChallenge({ onLeave }: { onLeave: () => void }) {
  const [failed, setFailed] = useState<string | null>(null)
  // Set once for each time the screen is opened, however often React runs this while developing.
  const asked = useRef(false)

  useEffect(() => {
    if (asked.current) return
    asked.current = true
    setChallenge().then(
      (code) => window.location.replace(`#/blackjack/challenge-${code}`),
      (problem) => setFailed(problem instanceof Refused ? problem.message : 'The challenge could not be set. Try again in a moment.'),
    )
  }, [])

  return (
    <CasinoShell>
      <Header detail="Blackjack challenge" onLeave={onLeave} />
      <Loading failed={failed !== null} message={failed ?? 'Shuffling a shoe of your own'} />
    </CasinoShell>
  )
}

/**
 * How a challenge stands: who set it, its rules, and how everyone who has
 * taken it finished, best first. It is where a player lands once their own
 * go is over, and where the code and the link are to pass it on.
 */
export function ChallengeResults({
  challenge,
  missing,
  signInNeeded = false,
  onLeave,
}: {
  challenge: ChallengeView | null
  missing: boolean
  /** Set for a guest, who may look at a challenge but must sign in to take it. */
  signInNeeded?: boolean
  onLeave: () => void
}) {
  if (missing || !challenge) {
    return (
      <CasinoShell>
        <Header detail="Blackjack challenge" onLeave={onLeave} />
        <div className="m-auto max-w-md py-10 text-center">
          <p className="text-xl font-semibold text-ivory">There is no challenge with that code</p>
          <p className="pt-2 text-sm text-muted">Check it, or ask whoever sent it for it again.</p>
          <button type="button" className="btn btn--raise mt-6 w-full" onClick={onLeave}>
            Back to blackjack
          </button>
        </div>
      </CasinoShell>
    )
  }

  const mine = challenge.standings.find((standing) => standing.you)
  const finished = challenge.standings.filter((standing) => standing.chips !== null)
  const shareText = mine?.chips != null
    ? `I finished a Banca blackjack challenge on ${chips(mine.chips)} chips. Same shoe, your turn: code ${shownCode(challenge.code)}.`
    : `Take my Banca blackjack challenge: ten rounds from the same shoe. Code ${shownCode(challenge.code)}.`

  return (
    <CasinoShell>
      <Header detail="Blackjack challenge" onLeave={onLeave} />
      <div className="flex flex-col gap-4 py-4 sm:gap-5 sm:py-6">
        <section className="panel rise-in text-center">
          <p className="label text-gold!">{challenge.yours ? 'Your challenge' : `${challenge.by}\u2019s challenge`}</p>
          {mine?.place ? (
            <p className="pt-2 text-4xl font-bold tracking-tight text-gold-bright">
              {ordinal(mine.place)}
              <span className="text-xl font-semibold text-muted"> of {finished.length}</span>
            </p>
          ) : (
            <p className="pt-2 text-2xl font-semibold tracking-tight text-ivory">How it stands</p>
          )}
          {signInNeeded && (
            <p className="pt-3 text-sm leading-relaxed text-gold-bright">
              Challenges are for players who have signed in.{' '}
              <a href="#/profile" className="underline">
                Sign in on your profile
              </a>
              , then open the link again to take it.
            </p>
          )}
          <p className="pt-2 text-sm leading-relaxed text-ivory/80">
            {challenge.rounds} rounds from the same shoe, {chips(challenge.stack)} chips each and bets up to {challenge.maxBet}. Played the same way, everyone
            is dealt the same cards. Each player has one go, which begins with their first bet, and a go left early is placed below every one played
            through. Nobody&rsquo;s own chips are touched.
          </p>
        </section>

        <section className="panel rise-in" style={{ ['--rise-delay' as string]: '60ms' }}>
          <h2 className="label pb-3 text-gold!">Standings</h2>
          <ol className="challenge-standings">
            {challenge.standings.map((standing, index) => (
              <li key={`${standing.name}-${index}`} data-you={standing.you}>
                <span className="challenge-standings__place figure">{standing.place ? ordinal(standing.place) : '·'}</span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{standing.you ? 'You' : standing.name}</span>
                  {!standing.complete && (
                    <span className="block text-xs text-muted">
                      Left after {standing.roundsPlayed} {standing.roundsPlayed === 1 ? 'round' : 'rounds'}
                    </span>
                  )}
                </span>
                {standing.chips === null ? (
                  <span className="label">Playing</span>
                ) : (
                  <span className="figure font-semibold" data-tone={standing.chips > challenge.stack ? 'gain' : standing.chips < challenge.stack ? 'loss' : 'even'}>
                    {chips(standing.chips)}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </section>

        <section className="panel rise-in flex flex-col items-center gap-3 text-center" style={{ ['--rise-delay' as string]: '120ms' }}>
          <p className="text-sm leading-relaxed text-ivory/80">Send it on. Anyone with the code can take it, once.</p>
          <TableCode id={challenge.code} label="Challenge code" />
          <InviteButton table={challenge.by} text={shareText} label="Send it to a friend" prominent />
          <button
            type="button"
            className="btn btn--quiet w-full"
            onClick={() => {
              sound.click()
              onLeave()
            }}
          >
            Back to blackjack
          </button>
        </section>
      </div>
    </CasinoShell>
  )
}
