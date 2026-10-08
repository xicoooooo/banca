import { keepTabInside } from '../casino/useDialog'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { prefersReducedMotion } from '../casino/motion'
import type { TraceEvent } from './types'
import type { Reasoning } from './useTable'

const SUITS: Record<string, { symbol: string; red: boolean }> = {
  s: { symbol: '♠', red: false },
  h: { symbol: '♥', red: true },
  d: { symbol: '♦', red: true },
  c: { symbol: '♣', red: false },
}

function MiniCards({ cards }: { cards: string[] }) {
  return (
    <>
      {cards.map((card) => {
        const suit = SUITS[card[1]]
        return (
          <span key={card} className="mini-card" data-red={suit?.red ?? false}>
            {card[0] === 'T' ? '10' : card[0]}
            {suit?.symbol}
          </span>
        )
      })}
    </>
  )
}

/** The step in which Banca settles how it will play a hand. What it settled on is its detail, revealed afterwards. */
const MOOD_STEP = 'Settled on how to play'

/** What a step shows: the finding, a line of context, and the tool it came from. */
type Finding = { result?: ReactNode; note?: ReactNode; tool?: string; street?: string }

const percent = (value: unknown) => `${Math.round(Number(value) * 100)}%`
const capitalise = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

/** Reads what a tool returned into something a person would say. */
function findingOf(event: TraceEvent): Finding {
  const detail = event.detail
  if (!detail) return {}

  const arrow = detail.indexOf(' → ')
  if (arrow < 0) return { note: detail }

  const tool = detail.slice(0, arrow)
  try {
    const data = JSON.parse(detail.slice(arrow + 3)) as Record<string, unknown>

    switch (tool) {
      case 'get_game_state': {
        const board = data.board as string[]
        return {
          tool,
          street: String(data.street),
          result: <MiniCards cards={data.your_cards as string[]} />,
          note: (
            <>
              {board.length > 0 ? <MiniCards cards={board} /> : 'No board yet · '}
              Pot {Number(data.pot).toLocaleString('en-US')}
            </>
          ),
        }
      }
      case 'get_hand_equity':
        return { tool, result: percent(data.equity), note: `against ${data.against}` }
      case 'get_pot_odds':
        return Number(data.call_cost) > 0
          ? {
              tool,
              result: `${Number(data.call_cost).toLocaleString('en-US')} to call`,
              note: `${percent(data.pot_odds)} needed to break even`,
            }
          : { tool, result: 'Nothing to call' }
      case 'get_legal_actions':
        return { tool, note: (data.actions as string[]).map(capitalise).join(' · ') }
      default:
        return { tool }
    }
  } catch {
    return { tool }
  }
}

/** One decision the agent made, with the steps that led to it. */
type Turn = { steps: TraceEvent[]; street?: string }

function turnsOf(events: TraceEvent[]): Turn[] {
  const turns: Turn[] = []
  let steps: TraceEvent[] = []

  const close = () => {
    if (steps.length === 0) return
    const street = steps.map((step) => findingOf(step).street).find(Boolean)
    turns.push({ steps, street })
    steps = []
  }

  for (const event of events) {
    steps.push(event)
    if (event.kind === 'decision' || event.kind === 'fallback') close()
  }
  close()
  return turns
}

function Step({ event, state }: { event: TraceEvent; state: 'done' | 'active' }) {
  if (event.kind === 'decision') {
    return (
      <li className="timeline__step" data-kind="decision" data-state={state}>
        <span aria-hidden className="timeline__node" />
        <p className="timeline__action">Decision</p>
        <p className="timeline__decision">{event.label.replace(/^Decided to /, '')}</p>
      </li>
    )
  }

  const finding = findingOf(event)

  return (
    <li className="timeline__step" data-kind={event.kind} data-state={state}>
      <span aria-hidden className="timeline__node" />
      <p className="timeline__action">{event.label}</p>
      {finding.result && <p className="timeline__result figure">{finding.result}</p>}
      {finding.note && <p className="timeline__note figure">{finding.note}</p>}
      {finding.tool && <p className="timeline__tool">{finding.tool}</p>}
    </li>
  )
}

type ReasoningPanelProps = {
  reasoning: Reasoning
  name: string
  /** Whether the agent is working on a decision right now. */
  thinking: boolean
  onClose: () => void
}

/**
 * The agent's decision trace, as a timeline: each tool it reached for, what it
 * learned, and what it chose. While it thinks the steps arrive live; what each
 * one found is sent by the server only once the hand is over, and fills in then.
 *
 * Opened to watch the agent think, it steps aside when the agent decides, so
 * the choice is seen in the trace and then carried out on the table.
 */
export function ReasoningPanel({ reasoning, name, thinking, onClose }: ReasoningPanelProps) {
  const [closing, setClosing] = useState(false)
  const closeButton = useRef<HTMLButtonElement>(null)
  const end = useRef<HTMLDivElement>(null)

  // The table redraws many times while this is open, handing over a new
  // onClose each time. Holding the latest in a ref keeps `close` the same
  // function throughout, so the timers and listeners below are set up once.
  const latestOnClose = useRef(onClose)
  useEffect(() => {
    latestOnClose.current = onClose
  })

  const close = useCallback(() => {
    if (prefersReducedMotion()) return latestOnClose.current()
    setClosing(true)
  }, [])

  // Leave once the closing animation has played.
  useEffect(() => {
    if (!closing) return
    const timer = setTimeout(() => latestOnClose.current(), 250)
    return () => clearTimeout(timer)
  }, [closing])

  // Escape closes it, and focus goes back to where it was.
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    closeButton.current?.focus()

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
      keepTabInside(closeButton.current?.closest('[role="dialog"]'), event)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      before?.focus?.()
    }
  }, [close])

  const mood = reasoning.revealed ? reasoning.events.find((event) => event.label === MOOD_STEP)?.detail : null

  const decisions = reasoning.events.filter((event) => event.kind === 'decision' || event.kind === 'fallback').length

  // Opened mid-thought, it closes a beat after the decision lands in the trace.
  // Opened at any other time it is being read, so it stays.
  const watching = useRef({ live: thinking, decisions })
  useEffect(() => {
    if (!watching.current.live || decisions <= watching.current.decisions || reasoning.revealed) return
    const timer = setTimeout(close, 1100)
    return () => clearTimeout(timer)
  }, [decisions, reasoning.revealed, close])

  // While watching live, keep the newest step in view as the trace grows. A
  // trace opened to be read starts at the top and is left where the reader puts it.
  useEffect(() => {
    if (!watching.current.live) return
    end.current?.scrollIntoView({ block: 'end', behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
  }, [reasoning.events.length, thinking])

  const turns = turnsOf(reasoning.events)

  return (
    <div className="dossier-backdrop" onClick={close}>
      <section
        role="dialog"
        aria-modal="true"
        aria-label={`${name}'s reasoning`}
        data-closing={closing}
        onClick={(event) => event.stopPropagation()}
        className="dossier"
      >
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">Decision trace · Hand {reasoning.handNumber}</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">Inside {name}'s head</h2>
          </div>
          <button ref={closeButton} type="button" onClick={close} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div
          className="overflow-y-auto px-5"
          style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}
        >
          {/* How Banca was playing, told only once the hand is over and it can give nothing away. */}
          {mood && (
            <p className="mood rise-in">
              <span className="label text-gold!">Its mood this hand</span>
              {mood}
            </p>
          )}

          {turns.length === 0 && !thinking && (
            <p className="py-10 text-center text-muted">{name} has not had to decide anything yet.</p>
          )}

          {turns.map((turn, index) => (
            <div key={index}>
              <p className="timeline__turn label">
                Decision {index + 1}
                {turn.street ? ` · ${turn.street}` : ''}
              </p>
              <ol className="timeline">
                {turn.steps.map((step, stepIndex) => (
                  <Step key={stepIndex} event={step} state="done" />
                ))}
                {/* The step in progress: the agent has not said what it is yet. */}
                {thinking && index === turns.length - 1 && turn.steps.at(-1)?.kind === 'tool' && (
                  <li className="timeline__step" data-state="active">
                    <span aria-hidden className="timeline__node" />
                    <p className="timeline__action">Working</p>
                  </li>
                )}
              </ol>
            </div>
          ))}

          {/* The first turn, before any step. Later turns wait for their first
              step instead: a decision and the state that ends the turn arrive a
              moment apart, and a heading for a turn that is not coming must not
              flash up in between. */}
          {thinking && turns.length === 0 && (
            <div>
              <p className="timeline__turn label">Decision {turns.length + 1}</p>
              <ol className="timeline">
                <li className="timeline__step" data-state="active">
                  <span aria-hidden className="timeline__node" />
                  <p className="timeline__action">Thinking</p>
                </li>
              </ol>
            </div>
          )}

          {!reasoning.revealed && reasoning.events.length > 0 && (
            <p className="label pb-2 text-center leading-relaxed">
              What each step found is revealed when the hand is over
            </p>
          )}

          <div ref={end} />
        </div>
      </section>
    </div>
  )
}
