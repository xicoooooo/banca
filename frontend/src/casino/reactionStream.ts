import { useCallback, useEffect, useRef, useState } from 'react'
import type { ChatLine } from './room'

// A reaction is an emoji thrown onto the table: seen by everyone there for a
// moment and then gone. It travels as a line of chat marked as an emote, and
// is kept apart from what is said so that the two do not crowd each other.

export type Reaction = { id: number; from: string; text: string }

/** How long a reaction stays on the table, which the stylesheet's animation matches. */
export const REACTION_MS = 2_600

/** No more than this many are shown at once, however fast they come. */
const MOST_AT_ONCE = 8

/** The reactions on the table now, and the way to add one as it arrives. */
export function useReactionStream(): [Reaction[], (line: ChatLine) => void] {
  const [reactions, setReactions] = useState<Reaction[]>([])
  const next = useRef(1)
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>())

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach(clearTimeout)
  }, [])

  const add = useCallback((line: ChatLine) => {
    const id = next.current++
    setReactions((shown) => [...shown, { id, from: line.from, text: line.text }].slice(-MOST_AT_ONCE))
    const timer = setTimeout(() => {
      timers.current.delete(timer)
      setReactions((shown) => shown.filter((reaction) => reaction.id !== id))
    }, REACTION_MS)
    timers.current.add(timer)
  }, [])

  return [reactions, add]
}

/** Splits what a room remembers into what was said, leaving out any reactions an older server kept. */
export const spokenOnly = (lines: ChatLine[]) => lines.filter((line) => !line.emote)
