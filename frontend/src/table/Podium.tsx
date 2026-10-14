import { ordinal } from './places'
import type { TournamentView } from './types'

const PLACES = ['First', 'Second', 'Third']

/** Where everyone finished, once a tournament is over: the first three on a podium, then the rest. */
export function Podium({ tournament }: { tournament: TournamentView }) {
  const placed = [...tournament.standings].filter((seat) => seat.place !== null).sort((a, b) => a.place! - b.place!)
  const top = placed.slice(0, 3)
  const rest = placed.slice(3)
  // Second, first, third, as a podium stands.
  const stand = [top[1], top[0], top[2]].filter(Boolean)

  return (
    <section className="podium rise-in" aria-label="How the tournament finished">
      <p className="label text-center text-gold!">The tournament is over</p>
      <ol className="podium__steps">
        {stand.map((seat) => (
          <li key={seat.name} className="podium__step" data-place={seat.place} data-you={seat.you}>
            <span className="podium__name">{seat.you ? 'You' : seat.name}</span>
            <span className="podium__block">
              <span className="label">{PLACES[seat.place! - 1]}</span>
            </span>
          </li>
        ))}
      </ol>
      {rest.length > 0 && (
        <p className="pt-3 text-center text-sm text-muted">
          {rest.map((seat) => `${ordinal(seat.place!)} ${seat.you ? 'you' : seat.name}`).join(' · ')}
        </p>
      )}
    </section>
  )
}
