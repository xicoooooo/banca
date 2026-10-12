import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { variance } from './motion'
import type { Moment } from './moments'
import { sound } from './sound'

/** How long a celebration stays up, which the stylesheet's animations are timed to. */
const LASTS_MS = 3_800

const COLOURS = ['#f1c75b', '#f5f1e8', '#d9a441', '#7fe3bf', '#f1c75b', '#fff3c9']

// Fixed rather than random, so a celebration looks the same each time and nothing shifts on redraw.
const BITS = Array.from({ length: 30 }, (_, index) => ({
  left: `${4 + ((index * 37) % 92)}%`,
  delay: `${(index % 10) * 70 + Math.abs(variance(index + 1, 120))}ms`,
  fall: `${1900 + Math.abs(variance(index + 3, 700))}ms`,
  drift: `${variance(index + 5, 60)}px`,
  spin: `${variance(index + 9, 540)}deg`,
  colour: COLOURS[index % COLOURS.length],
  wide: index % 3 === 0,
}))

/**
 * The table stopping for a moment worth it: a rare hand, the number that came
 * in, a run of wins. It plays once for each moment, [delay] milliseconds after
 * the moment is known, so that the cards or the ball are seen first. It lies
 * over the table without getting in the way of it: nothing under it is blocked.
 */
export function Celebration({ moment, delay = 0 }: { moment: Moment | null; delay?: number }) {
  const [showing, setShowing] = useState<Moment | null>(null)
  // A moment is known by its key. Once known it is celebrated, even if the
  // table has moved on by the time its turn comes: a player quick to ask for
  // the next round should not lose the moment for it.
  const key = moment?.key ?? null
  const latest = useRef(moment)
  const waiting = useRef<{ key: string; timer: ReturnType<typeof setTimeout> } | null>(null)
  const played = useRef<string | null>(null)

  useEffect(() => {
    if (moment) latest.current = moment
  })

  useEffect(() => {
    if (key === null || played.current === key || waiting.current?.key === key) return
    if (waiting.current) clearTimeout(waiting.current.timer)
    const timer = setTimeout(() => {
      waiting.current = null
      played.current = key
      const now = latest.current
      if (!now || now.key !== key) return
      setShowing(now)
      sound.fanfare()
    }, delay)
    waiting.current = { key, timer }
  }, [key, delay])

  // Leaving the table is the one thing that calls it off.
  useEffect(
    () => () => {
      if (waiting.current) clearTimeout(waiting.current.timer)
      waiting.current = null
    },
    [],
  )

  useEffect(() => {
    if (!showing) return
    const end = setTimeout(() => setShowing(null), LASTS_MS)
    return () => clearTimeout(end)
  }, [showing])

  if (!showing) return null

  return (
    <div className="celebration" role="status" key={showing.key}>
      <div aria-hidden className="celebration__glow" />
      <div aria-hidden className="celebration__bits">
        {BITS.map((bit, index) => (
          <span
            key={index}
            className="celebration__bit"
            data-wide={bit.wide}
            style={
              {
                left: bit.left,
                background: bit.colour,
                '--delay': bit.delay,
                '--fall': bit.fall,
                '--drift': bit.drift,
                '--spin': bit.spin,
              } as CSSProperties
            }
          />
        ))}
      </div>
      <div className="celebration__words">
        <p className="celebration__title">{showing.title}</p>
        <p className="celebration__detail">{showing.detail}</p>
      </div>
    </div>
  )
}
