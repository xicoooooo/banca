import { BancaPill } from '../casino/BancaPill'
import { useDialog } from '../casino/useDialog'
import { playOf, type PokerCoaching } from './coaching'
import type { PokerAdvice, TraceEvent } from './types'

// What the coach is doing, said as something in progress.
const IN_PROGRESS: Record<string, string> = {
  'Looked at your hand': 'Reading your hand',
  'Checked what you may do': 'Checking your options',
  'Estimated how often you win': 'Counting your chances',
  'Worked out the price of calling': 'Pricing the call',
  'Thought it over': 'Thinking it over',
}

const percent = (chance: number) => `${Math.round(chance * 100)}%`

/**
 * The coach, beside the player's own controls: a button to ask it, the steps
 * it takes while it works, and then what it advises. It is not the Banca across
 * the table. It is given this player's view of the hand and nothing more.
 */
export function PokerCoachPill({ coach, onAsk, onOpen }: { coach: PokerCoaching; onAsk: () => void; onOpen: () => void }) {
  const thinking = coach.status === 'thinking'
  const steps = coach.steps.filter((step) => step.kind === 'tool')
  const latest = steps.at(-1)

  return (
    <BancaPill
      compact
      thinking={thinking}
      label={
        coach.advice ? `Coach · ${playOf(coach.advice)}` : thinking ? (latest ? (IN_PROGRESS[latest.label] ?? latest.label) : 'Thinking') : 'Ask the coach'
      }
      stepsDone={steps.length}
      stepsInAll={4}
      describedAs={
        coach.advice
          ? `The coach advises: ${playOf(coach.advice)}. Open the reasoning.`
          : thinking
            ? 'The coach is thinking. Open the reasoning.'
            : 'Ask the coach what to do'
      }
      onPress={coach.status === 'idle' ? onAsk : onOpen}
    />
  )
}

/**
 * The coach's one sentence, above the buttons it is about. Only where the
 * screen is tall enough to spare the lines; on a phone it is a press away, in
 * the panel the pill opens.
 */
export function PokerCoachReason({ coach }: { coach: PokerCoaching }) {
  if (!coach.advice) return null
  return (
    <p role="status" className="coach-reason coach-reason--roomy rise-in">
      {coach.advice.reason}
    </p>
  )
}

/** Reads what a tool told the coach into a line a person would say. */
function findingOf(step: TraceEvent): string | null {
  const detail = step.detail
  if (!detail) return null
  if (step.kind !== 'tool') return detail

  try {
    const data = JSON.parse(detail.slice(detail.indexOf('{'))) as Record<string, unknown>
    if ('your_cards' in data) {
      const board = (data.board as string[]).length
      return `${(data.your_cards as string[]).join(' ')} · ${board === 0 ? 'before the flop' : `${board} cards on the board`} · pot ${Number(data.pot).toLocaleString('en-US')}`
    }
    if ('equity' in data) return `You win about ${percent(Number(data.equity))} of the time against ${String(data.against)}`
    if ('pot_odds' in data) {
      return Number(data.call_cost) === 0 ? 'Nothing to call' : `A call of ${Number(data.call_cost).toLocaleString('en-US')} must win ${percent(Number(data.pot_odds))} of the time to pay`
    }
    if ('actions' in data) return `You may ${(data.actions as string[]).join(', ')}`
    return null
  } catch {
    return null
  }
}

function Figures({ advice }: { advice: PokerAdvice }) {
  const { figures } = advice
  const facingABet = figures.callCost > 0
  const rows = [
    { label: 'You win', value: figures.equity, best: true },
    ...(figures.againstABet !== null ? [{ label: 'Facing a bet', value: figures.againstABet, best: true }] : []),
    ...(facingABet ? [{ label: 'A call needs', value: figures.potOdds, best: false }] : []),
  ]

  return (
    <>
      <h3 className="label pt-5 pb-2.5">The figures</h3>
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {rows.map((row) => (
          <li key={row.label} className="play-value" data-best={row.best}>
            <span className="text-sm text-ivory">{row.label}</span>
            <span aria-hidden className="play-value__track">
              <span style={{ width: `${Math.max(4, Math.round(row.value * 100))}%` }} />
            </span>
            <span className="figure text-sm font-semibold">{percent(row.value)}</span>
          </li>
        ))}
      </ul>
      <p className="pt-2.5 text-xs leading-relaxed text-muted">
        How often your hand wins if it is played to the end against {figures.opponents === 1 ? 'one random hand' : `${figures.opponents} random hands`}
        {facingABet && `, and how often a call of ${figures.callCost.toLocaleString('en-US')} into a pot of ${figures.pot.toLocaleString('en-US')} must win to pay for itself`}
        .{' '}
        {facingABet && 'Someone who bets usually holds better than a random hand, so the second figure marks the first down by ten points to allow for it.'}
      </p>
    </>
  )
}

type PokerCoachPanelProps = { coach: PokerCoaching; handNumber: number; onClose: () => void }

/**
 * Everything behind the advice: the figures, and each step the coach took.
 * Nothing is held back, because the coach knows only what the player can see.
 */
export function PokerCoachPanel({ coach, handNumber, onClose }: PokerCoachPanelProps) {
  const closeButton = useDialog(onClose)
  const { advice } = coach

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label="The coach's advice" onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">Coach · Hand {handNumber}</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">
              {advice ? `The coach would ${playOf(advice).toLowerCase()}` : 'The coach is thinking'}
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
              <Figures advice={advice} />
              <p className="pt-2.5 text-xs leading-relaxed text-muted">
                This is a read, not an answer. Poker has no single right play, and the coach cannot know what anyone else holds.
                {advice.source === 'book' && ' The coach could not put this one into its own words, so the advice is the rule of thumb for these figures.'}
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

          <p className="pt-2 text-xs leading-relaxed text-muted">
            The coach is not the Banca you are playing against. It is given your view of the table and nothing else, and the two share nothing.
          </p>
        </div>
      </section>
    </div>
  )
}
