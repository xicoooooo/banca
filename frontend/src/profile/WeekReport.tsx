import { sound } from '../casino/sound'
import type { WeeklyReport } from '../player/types'
import { signed, weekInWords } from './format'

/** What the button under the one thing to work on says, by where it leads. */
const GO: Record<string, string> = {
  poker: 'Play Hold’em',
  blackjack: 'Play blackjack',
  'blackjack/drill': 'Take a drill',
  roulette: 'Play roulette',
}

/**
 * Banca's report on a week of the player's play: what each game came to and
 * how it was played, and the one thing it would have them work on. Every
 * figure is from their own rounds.
 */
export function WeekReport({ report }: { report: WeeklyReport }) {
  const { focus } = report
  const tone = report.net > 0 ? 'gain' : report.net < 0 ? 'loss' : 'even'

  return (
    <>
      <p className="pb-4 text-sm leading-relaxed text-ivory/80">
        {weekInWords(report.week)}: {report.rounds} rounds, and{' '}
        <span className="figure font-semibold" data-tone={tone}>
          {report.net === 0 ? 'even' : signed(report.net)}
        </span>{' '}
        at the tables{report.current ? ' so far' : ''}.
      </p>

      <ul className="report-notes">
        {report.notes.map((note) => (
          <li key={note.title}>
            <p className="report-notes__title">{note.title}</p>
            <p className="report-notes__text">{note.text}</p>
          </li>
        ))}
      </ul>

      {focus ? (
        <div className="report-focus">
          <p className="label text-gold!">One thing to work on</p>
          <p className="pt-1.5 text-lg font-semibold tracking-tight text-ivory">{focus.title}</p>
          <p className="pt-1 text-sm leading-relaxed text-ivory/85">{focus.text}</p>
          {focus.go && GO[focus.go] && (
            <button
              type="button"
              className="btn btn--raise mt-3 w-full"
              onClick={() => {
                sound.click()
                window.location.hash = `/${focus.go}`
              }}
            >
              {GO[focus.go]}
            </button>
          )}
        </div>
      ) : (
        <p className="pt-4 text-sm leading-relaxed text-muted">Nothing stood out as needing work. Keep playing the way you are.</p>
      )}
    </>
  )
}
