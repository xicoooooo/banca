import type { CSSProperties } from 'react'
import type { TraceEvent } from './types'
import type { Reasoning } from './useTable'

/** Pulls the figures worth showing out of what a tool returned. */
function summarise(detail: string): { tool: string; facts: string[] } | null {
  const arrow = detail.indexOf(' → ')
  if (arrow < 0) return null

  const tool = detail.slice(0, arrow)
  try {
    const data = JSON.parse(detail.slice(arrow + 3)) as Record<string, unknown>
    const percent = (value: unknown) => `${Math.round(Number(value) * 100)}%`

    switch (tool) {
      case 'get_game_state':
        return {
          tool,
          facts: [
            `Cards ${(data.your_cards as string[]).join(' ')}`,
            (data.board as string[]).length > 0 ? `Board ${(data.board as string[]).join(' ')}` : 'No board yet',
            `Pot ${data.pot}`,
          ],
        }
      case 'get_hand_equity':
        return { tool, facts: [`Equity ${percent(data.equity)}`, `against ${data.against}`] }
      case 'get_pot_odds':
        return {
          tool,
          facts: Number(data.call_cost) > 0
            ? [`${data.call_cost} to call`, `needs ${percent(data.pot_odds)} to break even`]
            : ['Nothing to call'],
        }
      case 'get_legal_actions':
        return { tool, facts: [`May ${(data.actions as string[]).join(', ')}`] }
      default:
        return { tool, facts: [] }
    }
  } catch {
    return { tool, facts: [] }
  }
}

function Step({ event, index }: { event: TraceEvent; index: number }) {
  const summary = event.detail ? summarise(event.detail) : null
  const isDecision = event.kind === 'decision'

  return (
    <li
      className="rise-in flex gap-3 border-b border-white/5 py-2.5 last:border-0"
      style={{ '--rise-delay': `${Math.min(index, 12) * 35}ms` } as CSSProperties}
    >
      <span
        aria-hidden
        className={`mt-1.5 h-1.5 w-1.5 flex-none rounded-full ${
          isDecision ? 'bg-gold-bright' : event.kind === 'fallback' ? 'bg-red-400' : 'bg-emerald-300/70'
        }`}
      />
      <div className="min-w-0">
        <p className={isDecision ? 'font-semibold text-gold-bright' : 'text-ivory'}>{event.label}</p>

        {summary && summary.facts.length > 0 && (
          <p className="figure pt-0.5 text-sm text-muted">{summary.facts.join(' · ')}</p>
        )}
        {summary && <p className="pt-0.5 font-mono text-[0.65rem] tracking-wide text-white/30">{summary.tool}</p>}
        {event.detail && !summary && <p className="pt-0.5 text-sm text-muted">{event.detail}</p>}
      </div>
    </li>
  )
}

/**
 * The agent's account of a hand: every tool it called and what it decided.
 * During the hand only the steps are known; what each returned arrives from
 * the server once the hand is over.
 */
export function ReasoningPanel({
  reasoning,
  name,
  onClose,
}: {
  reasoning: Reasoning
  name: string
  onClose: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/55 sm:items-center" onClick={onClose}>
      <section
        role="dialog"
        aria-modal="true"
        aria-label={`${name}'s reasoning`}
        onClick={(event) => event.stopPropagation()}
        className="glass glass--strong rise-in flex max-h-[82dvh] w-full max-w-lg flex-col rounded-t-3xl p-5 sm:rounded-3xl"
        style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}
      >
        <header className="flex items-start justify-between gap-4 pb-3">
          <div>
            <p className="label">Agent · Hand {reasoning.handNumber}</p>
            <h2 className="pt-1 text-lg font-semibold text-ivory">How {name} played it</h2>
          </div>
          <button type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        {reasoning.events.length === 0 ? (
          <p className="py-8 text-center text-muted">{name} has not had to decide anything yet.</p>
        ) : (
          <ol className="overflow-y-auto pr-1">
            {reasoning.events.map((event, index) => (
              <Step key={index} event={event} index={index} />
            ))}
          </ol>
        )}

        {!reasoning.revealed && reasoning.events.length > 0 && (
          <p className="label pt-4 text-center leading-relaxed">
            What each step found stays hidden until the hand is over
          </p>
        )}
      </section>
    </div>
  )
}
