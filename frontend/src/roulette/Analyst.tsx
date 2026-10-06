import { BancaPill } from '../casino/BancaPill'
import { useDialog } from '../casino/useDialog'
import { colorOf } from './layout'
import type { ReadStep } from './types'
import type { Reading } from './useRoulette'

const percent = (share: number) => `${Math.round(share * 100)}%`

/** Banca at the roulette table: asked, it reads the layout the player has put down. */
export function AnalystPill({ reading, canAsk, onAsk, onOpen }: { reading: Reading; canAsk: boolean; onAsk: () => void; onOpen: () => void }) {
  const thinking = reading.status === 'thinking'
  const steps = reading.steps.filter((step) => step.kind === 'tool')

  // With nothing on the felt there is nothing to ask about.
  if (reading.status === 'idle' && !canAsk) return null

  return (
    <BancaPill
      thinking={thinking}
      // Beside the wheel there is room for a word or two; the steps are named in full in the panel.
      compact
      label={reading.read ? 'The read' : thinking ? 'Thinking' : 'Ask Banca'}
      stepsDone={steps.length}
      stepsInAll={3}
      describedAs={reading.read ? "Open Banca's read of your bets" : thinking ? 'Banca is thinking. Open its working.' : 'Ask Banca what it makes of your bets'}
      onPress={reading.status === 'idle' ? onAsk : onOpen}
    />
  )
}

/** Reads what a tool told Banca into a line a person would say. */
function findingOf(step: ReadStep): string | null {
  const detail = step.detail
  if (!detail) return null
  if (step.kind !== 'tool') return detail

  try {
    const data = JSON.parse(detail.slice(detail.indexOf('{'))) as Record<string, unknown>
    if ('total_staked' in data) {
      const bets = data.bets as unknown[]
      return `${bets.length} ${bets.length === 1 ? 'bet' : 'bets'} · ${Number(data.total_staked).toLocaleString('en-US')} staked`
    }
    if ('come_out_ahead' in data) return `Ahead ${percent(Number(data.come_out_ahead))} · Nothing back ${percent(Number(data.lose_everything_staked))}`
    if ('average_per_spin' in data) return `About ${Math.abs(Number(data.average_per_spin)).toFixed(1)} chips a spin, on average`
    return null
  } catch {
    return null
  }
}

/**
 * Everything behind the read: how often the layout wins, what it can win, and
 * what it costs. These figures are worked out on the server by counting every
 * pocket, and are the same whatever words Banca chose.
 */
export function AnalystPanel({ reading, onClose }: { reading: Reading; onClose: () => void }) {
  const closeButton = useDialog(onClose)

  const read = reading.read
  const figures = read?.figures
  // The wheel's 37 pockets, shared out by what each would do to this layout.
  const outcomes = figures
    ? [
        { label: 'Come out ahead', share: figures.ahead, tone: 'gain' },
        { label: 'Break even', share: figures.level, tone: 'even' },
        { label: 'Win something, lose overall', share: figures.behind, tone: 'part' },
        { label: 'Lose everything staked', share: figures.nothing, tone: 'loss' },
      ].filter((outcome) => outcome.share > 0)
    : []

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label="Banca's read of your bets" onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">Banca · Your layout</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">{read ? 'What these bets stand to do' : 'Banca is thinking'}</h2>
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="overflow-y-auto px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          {read && figures && (
            <>
              <p className="text-sm leading-relaxed text-ivory/85">{read.text}</p>

              <h3 className="label pt-5 pb-2.5">One spin, out of 37 pockets</h3>
              <div className="wheel-share" role="img" aria-label={outcomes.map((outcome) => `${outcome.label} ${percent(outcome.share)}`).join(', ')}>
                {outcomes.map((outcome) => (
                  <span key={outcome.label} data-tone={outcome.tone} style={{ flexGrow: outcome.share }} />
                ))}
              </div>
              <ul className="m-0 flex list-none flex-col gap-1.5 p-0 pt-3">
                {outcomes.map((outcome) => (
                  <li key={outcome.label} className="flex items-center justify-between gap-3 text-sm">
                    <span className="flex items-center gap-2 text-ivory/85">
                      <span aria-hidden className="wheel-share__key" data-tone={outcome.tone} />
                      {outcome.label}
                    </span>
                    <span className="figure font-semibold text-ivory">{percent(outcome.share)}</span>
                  </li>
                ))}
              </ul>

              <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-3 pt-5">
                <div>
                  <dt className="label tracking-[0.12em]!">Best spin</dt>
                  <dd className="figure m-0 pt-1 text-base font-semibold" data-tone={figures.best > 0 ? 'gain' : 'loss'}>
                    {figures.best > 0 ? '+' : figures.best < 0 ? '−' : ''}
                    {Math.abs(figures.best).toLocaleString('en-US')}
                    {figures.bestPockets.length <= 3 && (
                      <span className="label pl-1.5 tracking-[0.08em]!">
                        on {figures.bestPockets.map((pocket) => `${pocket}${pocket === 0 ? '' : ` ${colorOf(pocket)}`}`).join(', ')}
                      </span>
                    )}
                  </dd>
                </div>
                <div>
                  <dt className="label tracking-[0.12em]!">Average spin</dt>
                  <dd className="figure m-0 pt-1 text-base font-semibold" data-tone="loss">
                    −{Math.abs(figures.average).toFixed(1)}
                  </dd>
                </div>
              </dl>
              <p className="pt-3 text-xs leading-relaxed text-muted">
                The house keeps 2.7% of every bet on this wheel, whichever bets they are. No layout or system changes that,
                and where the ball has been says nothing about where it goes next.
                {read.source === 'book' && ' Banca could not put this one into its own words, so the read comes straight from the figures.'}
              </p>
            </>
          )}

          <h3 className="timeline__turn label">How it got there</h3>
          <ol className="timeline">
            {reading.steps.map((step, index) => (
              <li key={index} className="timeline__step" data-kind={step.kind} data-state="done">
                <span aria-hidden className="timeline__node" />
                <p className="timeline__action">{step.label}</p>
                {step.kind !== 'decision' && findingOf(step) && <p className="timeline__note figure">{findingOf(step)}</p>}
              </li>
            ))}
            {reading.status === 'thinking' && (
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
