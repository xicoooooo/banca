import { useState } from 'react'
import { sound } from '../casino/sound'
import { useCountdown } from '../casino/useChipNotices'
import { claimMission } from '../player/api'
import type { MissionsStatus } from '../player/types'
import { chips } from '../profile/format'

type MissionsProps = {
  missions: MissionsStatus
  onClaimed: () => void
}

/**
 * Today's three missions: what each asks, how far along the player is, and a
 * few chips to collect for each one done, with a little more for all three.
 * They are new every day, and the server works out the progress from the
 * rounds actually played.
 */
export function Missions({ missions, onClaimed }: MissionsProps) {
  const [claiming, setClaiming] = useState<number | null>(null)
  const [problem, setProblem] = useState(false)
  const resets = useCountdown(missions.resetsAt)
  const done = missions.missions.filter((mission) => mission.progress >= mission.target).length
  const bonusSlot = missions.missions.length

  const claim = async (slot: number) => {
    if (claiming !== null) return
    setClaiming(slot)
    setProblem(false)
    try {
      await claimMission(slot)
      sound.chipStack()
      onClaimed()
    } catch {
      setProblem(true)
    }
    setClaiming(null)
  }

  return (
    <section className="reward rise-in" style={{ ['--rise-delay' as string]: '90ms' }} aria-label="Today's missions">
      <div className="flex items-baseline justify-between gap-3">
        <p className="label text-gold!">Today's missions</p>
        <p className="label tracking-[0.1em]!">{resets ? `New in ${resets}` : 'New ones soon'}</p>
      </div>

      <ul className="missions">
        {missions.missions.map((mission) => (
          <li key={mission.slot} className="mission" data-state={mission.claimed ? 'claimed' : mission.ready ? 'ready' : 'going'}>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold tracking-tight text-ivory">{mission.title}</p>
              <p className="text-xs leading-snug text-muted">{mission.detail}</p>
              <div
                className="mission__bar"
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={mission.target}
                aria-valuenow={mission.progress}
                aria-label={`${mission.title}: ${mission.progress} of ${mission.target}`}
              >
                <span style={{ width: `${(mission.progress / mission.target) * 100}%` }} />
              </div>
            </div>

            {mission.ready ? (
              <button type="button" className="btn btn--raise px-3.5! py-2! text-sm" onClick={() => claim(mission.slot)} disabled={claiming !== null}>
                Claim <span className="figure">{chips(mission.reward)}</span>
              </button>
            ) : (
              <p className="mission__worth figure">
                {mission.claimed ? (
                  <>
                    <span aria-hidden>✓ </span>
                    <span className="sr-only">Claimed, </span>
                    {chips(mission.reward)}
                  </>
                ) : (
                  <>
                    <span className="block text-ivory/85">
                      {mission.progress}/{mission.target}
                    </span>
                    +{chips(mission.reward)}
                  </>
                )}
              </p>
            )}
          </li>
        ))}
      </ul>

      {/* A little more for doing all three, which is the reason to do the third. */}
      <div className="mission mission--bonus" data-state={missions.bonus.claimed ? 'claimed' : missions.bonus.ready ? 'ready' : 'going'}>
        <p className="min-w-0 flex-1 text-sm text-ivory/85">
          {missions.bonus.claimed ? 'All three done. See you tomorrow.' : `All three · ${done} of ${missions.missions.length} done`}
        </p>
        {missions.bonus.ready ? (
          <button type="button" className="btn btn--raise px-3.5! py-2! text-sm" onClick={() => claim(bonusSlot)} disabled={claiming !== null}>
            Claim <span className="figure">{chips(missions.bonus.reward)}</span>
          </button>
        ) : (
          <p className="mission__worth figure">
            {missions.bonus.claimed ? '✓ ' : '+'}
            {chips(missions.bonus.reward)}
          </p>
        )}
      </div>

      {problem && (
        <p role="alert" className="pt-2.5 text-sm text-gold-bright">
          Could not claim it just now. Try again in a moment.
        </p>
      )}
    </section>
  )
}
