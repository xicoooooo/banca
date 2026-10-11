import { useCallback, useState } from 'react'

// "Read Banca": once a hand is over and before its mood is shown, the player
// may say which mood they think it was in. Nothing rides on it but the count,
// which is kept on this device.

export type MoodName = 'steady' | 'patient' | 'pressing' | 'sly'

/** The moods there are to choose from, each with the way to tell it. */
export const MOODS: { name: MoodName; title: string; tell: string }[] = [
  { name: 'steady', title: 'Steady', tell: 'Plays it straight' },
  { name: 'patient', title: 'Patient', tell: 'Waits, and respects a bet' },
  { name: 'pressing', title: 'Pressing', tell: 'Bets and raises a lot' },
  { name: 'sly', title: 'Sly', tell: 'Hides its strong hands' },
]

/** Which mood the server's words describe: they begin "In a sly mood". Null for words that name none. */
export function moodOf(told: string | null | undefined): MoodName | null {
  const named = told?.match(/^In an? (\w+) mood/)?.[1]
  return MOODS.find((mood) => mood.name === named)?.name ?? null
}

/** How the player's reads have gone: how many, how many right, the run they are on and the best they have had. */
export type Reads = { total: number; right: number; streak: number; best: number }

export const NO_READS: Reads = { total: 0, right: 0, streak: 0, best: 0 }

export function afterRead(reads: Reads, right: boolean): Reads {
  const streak = right ? reads.streak + 1 : 0
  return { total: reads.total + 1, right: reads.right + (right ? 1 : 0), streak, best: Math.max(reads.best, streak) }
}

/** The count in a few words: "Right 7 of 12 · 3 in a row". */
export function readsInWords(reads: Reads): string {
  if (reads.total === 0) return 'No reads yet'
  const run = reads.streak > 1 ? ` · ${reads.streak} in a row` : ''
  return `Right ${reads.right} of ${reads.total}${run}`
}

const KEY = 'banca.reads'

export function keptReads(): Reads {
  try {
    const kept = JSON.parse(localStorage.getItem(KEY) ?? 'null') as Partial<Reads> | null
    const count = (value: unknown) => (typeof value === 'number' && value >= 0 ? Math.floor(value) : 0)
    return kept ? { total: count(kept.total), right: count(kept.right), streak: count(kept.streak), best: count(kept.best) } : NO_READS
  } catch {
    return NO_READS
  }
}

/** What the player said about one hand: a mood, or 'shown' when they asked to be told without guessing. */
export type Answer = { handNumber: number; said: MoodName | 'shown' }

export type ReadingBanca = {
  reads: Reads
  /** The answer given for the hand numbered, if one has been. */
  answerFor: (handNumber: number) => Answer['said'] | null
  /** Takes a guess at [was], the mood the hand was really played in. */
  guess: (handNumber: number, said: MoodName, was: MoodName) => void
  /** Shows the mood without a guess, which counts neither for nor against. */
  show: (handNumber: number) => void
}

/**
 * The player's reads at one table. What they answered is remembered for the
 * hand it was about, so the question is not asked twice; the count outlasts
 * the table.
 */
export function useReadingBanca(): ReadingBanca {
  const [reads, setReads] = useState(keptReads)
  const [answer, setAnswer] = useState<Answer | null>(null)

  const guess = useCallback((handNumber: number, said: MoodName, was: MoodName) => {
    setAnswer({ handNumber, said })
    setReads((before) => {
      const after = afterRead(before, said === was)
      try {
        localStorage.setItem(KEY, JSON.stringify(after))
      } catch {
        // A browser that will keep nothing still counts until the page is closed.
      }
      return after
    })
  }, [])
  const show = useCallback((handNumber: number) => setAnswer({ handNumber, said: 'shown' }), [])

  return { reads, answerFor: (handNumber) => (answer?.handNumber === handNumber ? answer.said : null), guess, show }
}
