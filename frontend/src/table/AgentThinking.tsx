import type { TraceEvent } from './types'
import type { Reasoning } from './useTable'

// What the agent is doing, said as something in progress.
const IN_PROGRESS: Record<string, string> = {
  'Looked at the table': 'Reading the table',
  'Checked what it may do': 'Weighing its options',
  'Estimated its hand equity': 'Estimating equity',
  'Worked out the pot odds': 'Working out pot odds',
  'Thought it over': 'Thinking it over',
}

/** The steps the agent has taken in the turn it is on now. */
function currentTurn(events: TraceEvent[]): TraceEvent[] {
  const lastDecision = events.findLastIndex((event) => event.kind === 'decision' || event.kind === 'fallback')
  return events.slice(lastDecision + 1)
}

type AgentThinkingProps = {
  reasoning: Reasoning
  thinking: boolean
  /** What the agent last did this street, shown once it has decided. */
  decided?: string
  onOpen: () => void
}

/**
 * The agent at work, shown as an instrument rather than a conversation.
 *
 * While it thinks, each tool it reaches for lights a step. What the tools told
 * it stays hidden until the hand is over, because its equity would give its
 * cards away; the full account opens from here afterwards.
 */
export function AgentThinking({ reasoning, thinking, decided, onOpen }: AgentThinkingProps) {
  const steps = thinking ? currentTurn(reasoning.events).filter((event) => event.kind === 'tool') : []
  const latest = steps.at(-1)
  const headline = thinking
    ? latest
      ? (IN_PROGRESS[latest.label] ?? latest.label)
      : 'Thinking'
    : (decided ?? (reasoning.events.length > 0 ? 'Reasoning' : 'Waiting'))

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label="Open the agent's reasoning"
      className="glass flex h-8 items-center gap-2.5 rounded-full px-3.5 transition hover:bg-black/30"
    >
      <span aria-hidden className={thinking ? 'orb' : 'orb orb--idle'} />

      <span aria-live="polite" className={`label ${thinking ? 'text-ivory!' : decided ? 'text-gold-bright!' : ''}`}>
        {headline}
      </span>

      {thinking && (
        // Four lookups make a full turn; each one taken lights a dot.
        <span aria-hidden className="flex gap-1">
          {[0, 1, 2, 3].map((index) => (
            <span key={index} className="step-dot" data-done={index < steps.length} />
          ))}
        </span>
      )}
    </button>
  )
}
