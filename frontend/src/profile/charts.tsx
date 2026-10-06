import { useId, useState, type PointerEvent } from 'react'
import type { Dashboard } from '../player/types'
import { areaPath, chips, divergingBars, linePath, plot, shortDate, signed, toneOf } from './format'

const WIDTH = 600
const HEIGHT = 180

/**
 * The bankroll after every change to it, oldest on the left. Points are spaced
 * evenly, one per change, rather than by the clock: rounds come in bursts, and
 * by the clock a whole evening would be a single vertical line.
 */
export function BankrollChart({ history }: { history: Dashboard['bankroll']['history'] }) {
  const gradient = useId()
  const [pointed, setPointed] = useState<number | null>(null)

  const values = history.map((point) => point.balance)
  const points = plot(values, WIDTH, HEIGHT)
  const high = Math.max(...values)
  const low = Math.min(...values)
  const shown = pointed ?? points.length - 1

  const point = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const share = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width))
    setPointed(Math.round(share * (points.length - 1)))
  }

  return (
    <figure className="m-0">
      <div className="flex items-baseline justify-between pb-2">
        <span className="label">
          {pointed === null ? 'Now' : shortDate(history[shown].at)}
        </span>
        <span className="figure text-sm text-ivory">{chips(values[shown])}</span>
      </div>

      <div
        className="chart"
        onPointerMove={point}
        onPointerDown={point}
        onPointerLeave={() => setPointed(null)}
        role="img"
        aria-label={`Bankroll over ${history.length - 1} changes, from ${chips(values[0])} to ${chips(values[values.length - 1])}. Highest ${chips(high)}, lowest ${chips(low)}.`}
      >
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" aria-hidden>
          <defs>
            <linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#f1c75b" stopOpacity="0.28" />
              <stop offset="1" stopColor="#f1c75b" stopOpacity="0" />
            </linearGradient>
          </defs>
          <path d={areaPath(points, HEIGHT)} fill={`url(#${gradient})`} />
          <path d={linePath(points)} className="chart__line" />
        </svg>

        {/* Drawn in HTML so it stays round whatever shape the chart is stretched to. */}
        <span
          aria-hidden
          className="chart__marker"
          data-pointed={pointed !== null}
          style={{ left: `${(points[shown].x / WIDTH) * 100}%`, top: `${(points[shown].y / HEIGHT) * 100}%` }}
        />
      </div>

      <figcaption className="label flex justify-between pt-2 tracking-[0.12em]!">
        <span>Low {chips(low)}</span>
        <span>High {chips(high)}</span>
      </figcaption>
    </figure>
  )
}

/** What each of the last fourteen days came to: up from the line for a winning day, down for a losing one. */
export function ActivityChart({ activity }: { activity: Dashboard['activity'] }) {
  const bars = divergingBars(activity.map((day) => day.net))
  const [pointed, setPointed] = useState<number | null>(null)
  const day = pointed === null ? null : activity[pointed]

  return (
    <figure className="m-0">
      <div className="flex items-baseline justify-between pb-2">
        <span className="label">{day ? shortDate(day.date) : 'Result by day'}</span>
        <span className="figure text-sm text-ivory">
          {day ? (day.rounds === 0 ? 'No play' : `${signed(day.net)} · ${day.rounds} ${day.rounds === 1 ? 'round' : 'rounds'}`) : ''}
        </span>
      </div>

      <div className="daybars" onPointerLeave={() => setPointed(null)}>
        {activity.map((entry, index) => (
          <button
            key={entry.date}
            type="button"
            className="daybars__day"
            data-tone={toneOf(entry.net)}
            data-played={entry.rounds > 0}
            data-pointed={pointed === index}
            onPointerEnter={() => setPointed(index)}
            onFocus={() => setPointed(index)}
            onBlur={() => setPointed(null)}
            aria-label={`${shortDate(entry.date)}: ${entry.rounds === 0 ? 'no play' : `${signed(entry.net)} over ${entry.rounds} rounds`}`}
            style={{ ['--size' as string]: Math.abs(bars[index]) }}
          >
            <span aria-hidden className="daybars__bar" />
          </button>
        ))}
      </div>

      <figcaption className="label flex justify-between pt-2 tracking-[0.12em]!">
        <span>{shortDate(activity[0].date)}</span>
        <span>{shortDate(activity[activity.length - 1].date)}</span>
      </figcaption>
    </figure>
  )
}
