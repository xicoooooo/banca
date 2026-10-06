import { useEffect, useRef } from 'react'
import { sound } from '../casino/sound'
import type { Advice, BlackjackAction, CoachStep } from './types'
import type { Coaching } from './useBlackjack'

const PLAY: Record<BlackjackAction, string> = {
  hit: 'Hit',
  stand: 'Stand',
  double: 'Double',
  split: 'Split',
  insure: 'Insure',
  decline_insurance: 'No insurance',
}

// What the coach is doing, said as something in progress.
const IN_PROGRESS: Record<string, string> = {
  'Looked at your hand': 'Reading your hand',
  'Worked out what each play is worth': 'Weighing each play',
  'Checked the odds of busting': 'Checking the odds',
  'Thought it over': 'Thinking it over',
}

/** A value per chip, said as chips won or lost for every hundred staked. */
function per100(value: number): string {
  const chips = Math.round(value * 100)
  return chips === 0 ? '0' : `${chips > 0 ? '+' : '−'}${Math.abs(chips)}`
}

/**
 * The coach, on the felt: a button to ask it, the steps it takes while it
 * works, and then what it advises. Asking is always the player's choice, and
 * the advice only marks a button; it never presses one.
 */
export function CoachPill({ coach, onAsk, onOpen }: { coach: Coaching; onAsk: () => void; onOpen: () => void }) {
  const thinking = coach.status === 'thinking'
  const steps = coach.steps.filter((step) => step.kind === 'tool')
  const latest = steps.at(-1)

  const press = () => {
    sound.click()
    if (coach.status === 'idle') onAsk()
    else onOpen()
  }

  return (
    <button
      type="button"
      onClick={press}
      aria-label={coach.advice ? `Banca advises: ${PLAY[coach.advice.action]}. Open the reasoning.` : thinking ? 'Banca is thinking. Open the reasoning.' : 'Ask Banca what to do'}
      className="glass rise-in flex h-9 items-center gap-2.5 rounded-full px-4 transition hover:bg-black/30"
    >
      <span aria-hidden className={thinking ? 'orb' : 'orb orb--idle'} />

      <span aria-live="polite" className={`label ${thinking ? 'text-ivory!' : 'text-gold-bright!'}`}>
        {coach.advice
          ? `Banca says · ${PLAY[coach.advice.action]}`
          : thinking
            ? latest
              ? (IN_PROGRESS[latest.label] ?? latest.label)
              : 'Thinking'
            : 'Ask Banca'}
      </span>

      {thinking && (
        // Three lookups make a full answer; each one taken lights a dot.
        <span aria-hidden className="flex gap-1">
          {[0, 1, 2].map((index) => (
            <span key={index} className="step-dot" data-done={index < steps.length} />
          ))}
        </span>
      )}
    </button>
  )
}

/** The coach's one sentence, above the buttons it is about. */
export function CoachReason({ advice }: { advice: Advice }) {
  return (
    <p role="status" className="coach-reason rise-in">
      {advice.reason}
    </p>
  )
}

/** Reads what a tool told the coach into a line a person would say. */
function findingOf(step: CoachStep): string | null {
  const detail = step.detail
  if (!detail) return null
  if (step.kind !== 'tool') return detail

  try {
    const data = JSON.parse(detail.slice(detail.indexOf('{'))) as Record<string, unknown>
    const percent = (value: unknown) => `${Math.round(Number(value) * 100)}%`

    if ('your_total' in data) {
      return `${data.soft ? 'Soft ' : ''}${data.your_total} against the dealer's ${String(data.dealer_shows)[0].replace('T', '10')}`
    }
    if ('dealer_busts' in data) {
      const yours = 'you_bust_if_you_hit' in data ? `You bust ${percent(data.you_bust_if_you_hit)} of the time by hitting · ` : ''
      return `${yours}Dealer busts ${percent(data.dealer_busts)}`
    }
    if ('best' in data) return `Best: ${PLAY[data.best as BlackjackAction] ?? String(data.best)}`
    return null
  } catch {
    return null
  }
}

type CoachPanelProps = { coach: Coaching; roundNumber: number; onClose: () => void }

/**
 * Everything behind the advice: what each play is worth, and each step the
 * coach took to get there. Nothing is held back, because the coach knows only
 * what the player can already see.
 */
export function CoachPanel({ coach, roundNumber, onClose }: CoachPanelProps) {
  const closeButton = useRef<HTMLButtonElement>(null)

  // Escape closes it, and focus goes back to where it was.
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    closeButton.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      before?.focus?.()
    }
  }, [onClose])

  const { advice } = coach
  // Bars are drawn from the worst play to the best, so the gap between them is what shows.
  const worst = advice ? Math.min(...advice.values.map((entry) => entry.value)) : 0
  const spread = advice ? Math.max(0.02, advice.values[0].value - worst) : 1

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label="Banca's advice" onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">Coach · Round {roundNumber}</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">
              {advice ? `Banca would ${PLAY[advice.action].toLowerCase().replace('no insurance', 'decline insurance')}` : 'Banca is thinking'}
            </h2>
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="overflow-y-auto px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          {advice && (
            <>
              <p className="text-sm leading-relaxed text-ivory/85">{advice.reason}</p>

              <h3 className="label pt-5 pb-2.5">What each play is worth</h3>
              <ul className="m-0 flex list-none flex-col gap-2 p-0">
                {advice.values.map((entry, index) => (
                  <li key={entry.action} className="play-value" data-best={index === 0}>
                    <span className="text-sm text-ivory">{PLAY[entry.action]}</span>
                    <span aria-hidden className="play-value__track">
                      <span style={{ width: `${8 + 92 * ((entry.value - worst) / spread)}%` }} />
                    </span>
                    <span className="figure text-sm font-semibold">{per100(entry.value)}</span>
                  </li>
                ))}
              </ul>
              <p className="label pt-2.5 leading-relaxed tracking-[0.08em]! normal-case">
                Chips won or lost on average for every 100 staked, if the hand were played many times.
                {advice.source === 'book' && ' Banca could not put this one into its own words, so the reason comes straight from the figures.'}
              </p>
            </>
          )}

          <h3 className="timeline__turn label">How it got there</h3>
          <ol className="timeline">
            {coach.steps.map((step, index) => (
              <li key={index} className="timeline__step" data-kind={step.kind} data-state="done">
                <span aria-hidden className="timeline__node" />
                <p className="timeline__action">{step.kind === 'decision' ? 'Advice' : step.label}</p>
                {step.kind === 'decision' ? (
                  <p className="timeline__decision">{step.label.replace(/^Advises you to /, '')}</p>
                ) : (
                  findingOf(step) && <p className="timeline__note figure">{findingOf(step)}</p>
                )}
              </li>
            ))}
            {coach.status === 'thinking' && (
              <li className="timeline__step" data-state="active">
                <span aria-hidden className="timeline__node" />
                <p className="timeline__action">Working</p>
              </li>
            )}
          </ol>
        </div>
      </section>
    </div>
  )
}
