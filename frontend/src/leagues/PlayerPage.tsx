import { useEffect, useState } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { fetchPublicProfile } from '../player/api'
import type { PublicProfile } from '../player/types'
import { chips, monthAndYear } from '../profile/format'
import { Crest } from './Crest'
import { Trophies } from './Trophies'

/**
 * Another player's page, reached from their name on a leaderboard: who they
 * are, how far they have come, and what they have won. It says nothing about
 * their chips or how they play; that is on their own profile, for them alone.
 */
export function PlayerPage({ id, onLeave }: { id: string; onLeave: () => void }) {
  const [profile, setProfile] = useState<PublicProfile | null>(null)
  const [missing, setMissing] = useState(false)

  useEffect(() => {
    let disposed = false
    fetchPublicProfile(id).then(
      (loaded) => {
        if (!disposed) setProfile(loaded)
      },
      () => {
        if (!disposed) setMissing(true)
      },
    )
    return () => {
      disposed = true
    }
  }, [id])

  return (
    <CasinoShell>
      <Header detail="Player" onLeave={onLeave} />

      {missing ? (
        <Loading failed message="This player could not be found." />
      ) : !profile ? (
        <Loading message="Finding the player" />
      ) : (
        <div className="flex flex-col gap-4 py-4 sm:gap-5 sm:py-6">
          <section className="panel rise-in flex items-center gap-4">
            <span aria-hidden className="monogram">
              {profile.name.trim().charAt(0).toUpperCase()}
            </span>
            <div className="min-w-0 flex-1">
              <h1 className="truncate text-2xl font-semibold tracking-tight text-ivory">{profile.name}</h1>
              <p className="label pt-1 tracking-[0.12em]!">
                Level {profile.level} · {profile.title} · Since {monthAndYear(profile.memberSince)}
              </p>
            </div>
          </section>

          <section className="panel rise-in" style={{ ['--rise-delay' as string]: '60ms' }}>
            <dl className="stats">
              <div className="stat flex items-center gap-3">
                <Crest league={profile.league} size={30} />
                <div>
                  <dt className="label tracking-[0.12em]!">League</dt>
                  <dd className="m-0 pt-1 text-lg font-semibold tracking-tight text-ivory">{profile.league}</dd>
                </div>
              </div>
              <div className="stat">
                <dt className="label tracking-[0.12em]!">Rounds played</dt>
                <dd className="figure m-0 pt-1.5 text-xl font-semibold tracking-tight text-ivory">{chips(profile.rounds)}</dd>
              </div>
              <div className="stat">
                <dt className="label tracking-[0.12em]!">Achievements</dt>
                <dd className="figure m-0 pt-1.5 text-xl font-semibold tracking-tight text-ivory">
                  {profile.achievements} of {profile.achievementsInAll}
                </dd>
              </div>
            </dl>
          </section>

          <section className="panel rise-in" style={{ ['--rise-delay' as string]: '120ms' }}>
            <div className="flex items-baseline justify-between gap-3 pb-4">
              <h2 className="label text-gold!">Trophies</h2>
              {profile.trophies.length > 0 && <span className="label tracking-[0.12em]!">{profile.trophies.length} won</span>}
            </div>
            <Trophies trophies={profile.trophies} whose="theirs" />
          </section>
        </div>
      )}
    </CasinoShell>
  )
}
