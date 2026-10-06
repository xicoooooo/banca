import { useState } from 'react'
import { claimDailyReward } from '../player/api'
import type { Broke } from '../player/types'
import { sound } from './sound'
import { useCountdown } from './useChipNotices'

/** A line saying the house has put chips in front of the player, so they do not appear unexplained. */
export function StakedNote({ amount }: { amount: number | null }) {
  if (amount === null) return null
  return (
    <p role="status" className="rise-in pb-2 text-center text-sm text-gold-bright">
      Out of chips, so the house has staked you {amount.toLocaleString('en-US')}
    </p>
  )
}

type OutOfChipsProps = {
  broke: Broke
  /** Asks the table to deal again, once there may be chips to play with. */
  onRetry: () => void
  onLeave?: () => void
}

/**
 * Shown in place of the controls when the table will not deal. Chips cannot be
 * bought, so this says exactly what the ways back are and how long they take.
 */
export function OutOfChips({ broke, onRetry, onLeave }: OutOfChipsProps) {
  const wait = useCountdown(broke.dailyReady ? null : broke.nextChipsAt)
  const [claiming, setClaiming] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const claim = async () => {
    setClaiming(true)
    try {
      await claimDailyReward()
      sound.chipStack()
      onRetry()
    } catch {
      setProblem('Could not claim it just now. Try again in a moment.')
      setClaiming(false)
    }
  }

  return (
    <section className="glass glass--strong rise-in mb-1 rounded-3xl px-5 py-4 text-center" aria-live="polite">
      <h2 className="text-lg font-semibold tracking-tight text-ivory">You are out of chips</h2>
      <p className="mx-auto max-w-sm pt-1 text-sm leading-relaxed text-muted">
        {broke.dailyReady
          ? 'Your daily reward is waiting. Claim it and you are back in.'
          : wait === 'now'
            ? 'The house is ready to stake you again.'
            : `Chips cannot be bought here. Your next ones arrive in ${wait}.`}
      </p>
      {problem && (
        <p role="alert" className="pt-2 text-sm text-gold-bright">
          {problem}
        </p>
      )}
      <div className="flex flex-wrap justify-center gap-2.5 pt-3.5">
        {broke.dailyReady ? (
          <button type="button" className="btn btn--raise px-5! text-sm" onClick={claim} disabled={claiming}>
            Claim daily reward
          </button>
        ) : (
          <button type="button" className="btn btn--call px-5! text-sm" onClick={onRetry}>
            Try again
          </button>
        )}
        {onLeave && (
          <button type="button" className="btn btn--quiet px-5! text-sm" onClick={onLeave}>
            Back to the lobby
          </button>
        )}
      </div>
    </section>
  )
}
