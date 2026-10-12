import { useState } from 'react'
import { sound } from '../casino/sound'
import { Refused, wearTitle } from '../player/api'
import type { Dashboard } from '../player/types'

/**
 * The titles a player has earned, one of which they may wear beside their
 * name where other players see it, and the ones still to be earned with what
 * each asks for. Pressing the one being worn takes it off.
 */
export function TitlePicker({ dashboard, onChanged }: { dashboard: Dashboard; onChanged: () => void }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const wearing = dashboard.player.wearing
  const mine = dashboard.titles.filter((title) => title.earned)
  const toEarn = dashboard.titles.filter((title) => !title.earned)

  const choose = async (name: string) => {
    sound.click()
    setBusy(name)
    setProblem(null)
    try {
      await wearTitle(name === wearing ? null : name)
      onChanged()
    } catch (failure) {
      setProblem(failure instanceof Refused ? failure.message : 'Could not reach the house. Try again in a moment.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <>
      <p className="pb-3 text-sm leading-relaxed text-ivory/80">
        Wear one beside your name, and the others at a shared table will see it. Press it again to take it off.
      </p>
      <div className="flex flex-wrap gap-2" role="group" aria-label="Titles you have earned">
        {mine.map((title) => (
          <button
            key={title.name}
            type="button"
            className="btn btn--quiet px-4! text-sm"
            data-selected={title.name === wearing}
            aria-pressed={title.name === wearing}
            data-pending={busy === title.name}
            disabled={busy !== null}
            onClick={() => choose(title.name)}
          >
            {title.name}
          </button>
        ))}
      </div>
      {problem && (
        <p role="alert" className="pt-3 text-sm text-gold-bright">
          {problem}
        </p>
      )}

      {toEarn.length > 0 && (
        <>
          <h3 className="label pt-5 pb-2">Still to earn</h3>
          <ul className="titles-to-earn">
            {toEarn.map((title) => (
              <li key={title.name}>
                <span className="font-semibold text-ivory/85">{title.name}</span>
                <span className="text-muted">{title.how}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  )
}
