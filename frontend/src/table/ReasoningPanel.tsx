import type { TraceEvent } from './types'
import type { Reasoning } from './useTable'

const MARKS: Record<TraceEvent['kind'], string> = {
  tool: '⚙',
  thought: '…',
  decision: '→',
  fallback: '!',
}

/**
 * How the opponent reached its decisions this hand. While the hand is live
 * only the steps are listed; what each step returned would give its cards
 * away, so the server sends that only afterwards.
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
    <div className="fixed inset-0 z-10 flex items-end justify-center bg-black/50 sm:items-center" onClick={onClose}>
      <section
        role="dialog"
        aria-label={`${name}'s reasoning`}
        onClick={(event) => event.stopPropagation()}
        className="flex max-h-[80dvh] w-full max-w-xl flex-col rounded-t-2xl bg-felt-800 p-4 shadow-xl sm:rounded-2xl"
      >
        <header className="flex items-center justify-between pb-3">
          <h2 className="font-semibold">How {name} played hand {reasoning.handNumber}</h2>
          <button type="button" onClick={onClose} className="rounded-lg bg-black/30 px-3 py-1 text-sm hover:bg-black/40">
            Close
          </button>
        </header>

        {reasoning.events.length === 0 ? (
          <p className="py-6 text-center text-sm text-white/60">{name} has not had to decide anything yet.</p>
        ) : (
          <ol className="flex flex-col gap-2 overflow-y-auto">
            {reasoning.events.map((event, index) => (
              <li key={index} className="rounded-xl bg-black/25 px-3 py-2">
                <p className={`flex gap-2 ${event.kind === 'decision' ? 'font-semibold text-chip-gold' : ''}`}>
                  <span aria-hidden className="w-4 shrink-0 text-center opacity-70">
                    {MARKS[event.kind]}
                  </span>
                  {event.label}
                </p>
                {event.detail && (
                  <p className="mt-1 pl-6 font-mono text-xs break-words whitespace-pre-wrap text-white/70">
                    {event.detail}
                  </p>
                )}
              </li>
            ))}
          </ol>
        )}

        {!reasoning.revealed && reasoning.events.length > 0 && (
          <p className="pt-3 text-center text-xs text-white/50">
            What each step returned stays hidden until the hand is over, or you would see its cards.
          </p>
        )}
      </section>
    </div>
  )
}
