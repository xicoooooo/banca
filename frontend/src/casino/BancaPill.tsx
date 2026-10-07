import { sound } from './sound'

type BancaPillProps = {
  thinking: boolean
  /** What the pill says: an invitation, the step in progress, or the answer. */
  label: string
  /** How many of its lookups Banca has made, and how many make a full answer. */
  stepsDone: number
  stepsInAll: number
  /** What pressing it will do, for a screen reader. */
  describedAs: string
  /** For where there is little room beside it. */
  compact?: boolean
  onPress: () => void
}

/**
 * Banca, wherever it sits beside the player rather than across from them: a
 * button to ask it, the steps it takes while it works, and then its answer.
 * Asking is always the player's choice.
 */
export function BancaPill({ thinking, label, stepsDone, stepsInAll, describedAs, compact = false, onPress }: BancaPillProps) {
  return (
    <button
      type="button"
      onClick={() => {
        sound.click()
        onPress()
      }}
      aria-label={describedAs}
      data-guide="banca"
      className={`glass rise-in flex h-9 items-center rounded-full transition hover:bg-black/30 ${compact ? 'gap-2 px-3' : 'gap-2.5 px-4'}`}
    >
      <span aria-hidden className={thinking ? 'orb' : 'orb orb--idle'} />

      <span aria-live="polite" className={`label whitespace-nowrap ${thinking ? 'text-ivory!' : 'text-gold-bright!'}`}>
        {label}
      </span>

      {thinking && (
        // Each lookup taken lights a dot.
        <span aria-hidden className="flex gap-1">
          {Array.from({ length: stepsInAll }, (_, index) => (
            <span key={index} className="step-dot" data-done={index < stepsDone} />
          ))}
        </span>
      )}
    </button>
  )
}
