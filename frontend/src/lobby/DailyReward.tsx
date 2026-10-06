import { useState } from 'react'
import { useCountdown } from '../casino/useChipNotices'
import { sound } from '../casino/sound'
import { claimDailyReward } from '../player/api'
import type { Rewards } from '../player/types'
import { chips } from '../profile/format'

type DailyRewardProps = {
  rewards: Rewards
  balance: number
  /** The smallest bet at any table; with less than this the player cannot play. */
  smallestBet: number
  onClaimed: () => void
}

/**
 * The week of daily rewards: what today's is worth, how far through the week
 * the player is, and, when they are out of chips, when their next ones come.
 */
export function DailyReward({ rewards, balance, smallestBet, onClaimed }: DailyRewardProps) {
  const { daily, rescue } = rewards
  const [claiming, setClaiming] = useState(false)
  const [problem, setProblem] = useState(false)
  const next = useCountdown(daily.nextAt)
  const stake = useCountdown(rescue.nextAt)

  // The days already claimed this week. A week just finished shows as full
  // until tomorrow's claim opens the next.
  const done = daily.available ? daily.day - 1 : daily.day === 1 ? daily.ladder.length : daily.day - 1

  const claim = async () => {
    setClaiming(true)
    setProblem(false)
    try {
      await claimDailyReward()
      sound.chipStack()
      onClaimed()
    } catch {
      setProblem(true)
    }
    setClaiming(false)
  }

  const broke = balance < smallestBet && !daily.available

  return (
    <section className="reward rise-in" style={{ ['--rise-delay' as string]: '60ms' }} aria-label="Daily reward">
      <div className="flex items-center gap-3">
        <div className="min-w-0 flex-1">
          <p className="label text-gold!">
            Daily reward{daily.streak > 1 ? ` · ${daily.streak} days running` : ''}
          </p>
          <p className="pt-1 text-sm leading-snug text-ivory/85">
            {daily.available ? (
              <>
                Day {daily.day} of {daily.ladder.length} is ready
              </>
            ) : (
              <>
                Next in {next} · <span className="figure">{chips(daily.amount)}</span> chips
              </>
            )}
          </p>
        </div>

        {daily.available && (
          <button type="button" className="btn btn--raise px-4! text-sm" onClick={claim} disabled={claiming}>
            Claim <span className="figure">{chips(daily.amount)}</span>
          </button>
        )}
      </div>

      <ol className="reward__week" aria-label="This week's rewards">
        {daily.ladder.map((amount, index) => {
          const state = index < done ? 'claimed' : index === done && daily.available ? 'today' : 'ahead'
          return (
            <li key={index} className="reward__day" data-state={state}>
              <span className="figure">{amount >= 1000 ? `${amount / 1000}k` : amount}</span>
              <span className="sr-only">
                {state === 'claimed' ? ' claimed' : state === 'today' ? ' ready today' : ` on day ${index + 1}`}
              </span>
            </li>
          )
        })}
      </ol>

      {problem && (
        <p role="alert" className="pt-2.5 text-sm text-gold-bright">
          Could not claim it just now. Try again in a moment.
        </p>
      )}
      {broke && (
        <p className="pt-2.5 text-sm leading-relaxed text-muted">
          {stake
            ? `You are out of chips. The house will stake you ${chips(rescue.amount)} in ${stake}.`
            : `You are out of chips. Sit down at a table and the house will stake you ${chips(rescue.amount)}.`}
        </p>
      )}
    </section>
  )
}
