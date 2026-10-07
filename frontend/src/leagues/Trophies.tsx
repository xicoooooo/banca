import type { Trophy } from '../player/types'
import { chips } from '../profile/format'
import { Crest } from './Crest'

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

/** "5 October 2026", from the Monday a week began on. */
function weekOf(day: string): string {
  const date = new Date(`${day}T00:00:00Z`)
  return `${date.getUTCDate()} ${MONTHS[date.getUTCMonth()]} ${date.getUTCFullYear()}`
}

/**
 * A player's trophies: every week they finished in the top three of their
 * league, newest first. Each is named and dated, and once won is never lost.
 */
export function Trophies({ trophies, whose }: { trophies: Trophy[]; whose: 'yours' | 'theirs' }) {
  if (trophies.length === 0) {
    return (
      <p className="rounded-2xl border border-dashed border-white/10 px-4 py-6 text-center text-sm leading-relaxed text-muted">
        {whose === 'yours' ? 'Finish in the top three of your league and the trophy is yours to keep.' : 'No trophies yet.'}
      </p>
    )
  }

  return (
    <ul className="m-0 grid list-none gap-2.5 p-0 sm:grid-cols-2">
      {trophies.map((trophy) => (
        <li key={`${trophy.week}-${trophy.league}`} className="trophy" data-champion={trophy.position === 1}>
          <Crest league={trophy.league} size={40} place={trophy.position} />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-ivory">{trophy.title}</p>
            <p className="label pt-0.5 tracking-[0.1em]!">Week of {weekOf(trophy.week)}</p>
            {whose === 'yours' && <p className="figure pt-0.5 text-xs text-muted">{chips(trophy.prize)} chips in prizes</p>}
          </div>
        </li>
      ))}
    </ul>
  )
}
