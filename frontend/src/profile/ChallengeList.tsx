import { useEffect, useState } from 'react'
import type { ChallengeView } from '../blackjack/types'
import { fetchMyChallenges } from '../player/api'
import { ordinal } from '../table/places'

/**
 * The blackjack challenges a player has set or taken, newest first, each with
 * how they stand in it and how many have taken it. Each opens to its standings.
 * Nothing is shown until there is one.
 */
export function ChallengeList() {
  const [challenges, setChallenges] = useState<ChallengeView[] | null>(null)

  useEffect(() => {
    let gone = false
    fetchMyChallenges().then(
      (found) => {
        if (!gone) setChallenges(found)
      },
      () => {
        if (!gone) setChallenges([])
      },
    )
    return () => {
      gone = true
    }
  }, [])

  if (!challenges || challenges.length === 0) return null

  return (
    <section className="panel rise-in" style={{ ['--rise-delay' as string]: '300ms' }}>
      <div className="flex items-baseline justify-between gap-3 pb-4">
        <h2 className="label text-gold!">Challenges</h2>
        <span className="label tracking-[0.12em]!">Blackjack</span>
      </div>
      <ul className="challenge-list">
        {challenges.map((challenge) => {
          const mine = challenge.standings.find((standing) => standing.you)
          const finished = challenge.standings.filter((standing) => standing.chips !== null).length
          return (
            <li key={challenge.code}>
              <a href={`#/blackjack/challenge-${challenge.code}`}>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold text-ivory">{challenge.yours ? 'Your challenge' : `${challenge.by}’s challenge`}</span>
                  <span className="block text-xs text-muted">
                    {finished === 0 ? 'Nobody has finished it yet' : `${finished} finished`}
                  </span>
                </span>
                <span className="label text-gold-bright!">
                  {mine?.place ? `${ordinal(mine.place)} of ${finished}` : challenge.taken ? 'Playing' : 'Not played'}
                </span>
              </a>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
