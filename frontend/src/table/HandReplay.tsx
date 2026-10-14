import { useEffect, useState, type CSSProperties } from 'react'
import { Card, CardSlot } from '../casino/Card'
import { sound } from '../casino/sound'
import { useDialog } from '../casino/useDialog'
import type { Replay, Thinking } from './replay'

const percent = (share: number) => `${Math.round(share * 100)}%`

/** Banca's thinking at one decision, said as a sentence. */
function thought(thinking: Thinking): string {
  const parts: string[] = []
  if (thinking.equity !== null) parts.push(`It reckoned its hand would win ${percent(thinking.equity)} of the time`)
  if (thinking.needed !== null) parts.push(`a call needed ${percent(thinking.needed)} to pay`)
  const reckoned = parts.join(', and ')
  return reckoned ? `${reckoned}, so it chose to ${thinking.decision}.` : `It chose to ${thinking.decision}.`
}

/**
 * A finished hand played back a step at a time: the board, the pot and every
 * seat as they stood after each action, and Banca's thinking beside each of
 * its decisions. Banca's own cards are shown throughout once it has revealed
 * them, which it does when the hand is over.
 */
export function HandReplay({ replay, onClose }: { replay: Replay; onClose: () => void }) {
  const closeButton = useDialog(onClose)
  const [at, setAt] = useState(0)
  const last = replay.steps.length - 1
  const step = replay.steps[at]
  const { view } = step

  const go = (to: number) => {
    if (to < 0 || to > last) return
    sound.click()
    setAt(to)
  }

  // The arrow keys step through it, as they would through a slideshow.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') setAt((now) => Math.min(now + 1, last))
      if (event.key === 'ArrowLeft') setAt((now) => Math.max(now - 1, 0))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [last])

  const board = [...view.board, null, null, null, null, null].slice(0, 5)

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label={`Replay of hand ${replay.handNumber}`} onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-3">
          <div>
            <p className="label text-gold!">Replay · Hand {replay.handNumber}</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">
              Step {at + 1} of {last + 1}
            </h2>
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="overflow-y-auto px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          <div className="replay-table">
            <div className="flex justify-center gap-1.5" style={{ '--w': '2.6rem' } as CSSProperties} aria-label="The board">
              {board.map((card, index) => (card ? <Card key={index} card={card} seed={index} /> : <CardSlot key={index} />))}
            </div>
            <p className="label pt-2 text-center">Pot {view.pot.toLocaleString('en-US')}</p>

            <ul className="replay-seats">
              {view.players.map((player) => {
                const you = player.seat === view.yourSeat
                return (
                  <li key={player.seat} data-acting={player.seat === step.seat} data-folded={player.status === 'folded'}>
                    <div className="flex gap-1" style={{ '--w': '1.9rem' } as CSSProperties}>
                      {[0, 1].map((index) => (
                        <Card key={index} card={player.cards?.[index] ?? null} seed={player.seat * 2 + index} />
                      ))}
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-semibold text-ivory">
                        {you ? 'You' : player.name}
                        {view.buttonSeat === player.seat && <span className="replay-dealer">D</span>}
                      </p>
                      <p className="figure text-xs text-muted">
                        {player.stack.toLocaleString('en-US')} behind
                        {player.status === 'folded' ? ' · folded' : player.status === 'all_in' ? ' · all in' : player.committed > 0 ? ` · ${player.committed.toLocaleString('en-US')} in front` : ''}
                      </p>
                    </div>
                  </li>
                )
              })}
            </ul>
          </div>

          <p className="replay-said rise-in" key={at} aria-live="polite">
            {step.text}.
          </p>
          {step.thinking && (
            <div className="replay-thinking rise-in" key={`t-${at}`}>
              <p className="label text-gold!">Banca's thinking</p>
              <p className="pt-1 text-sm leading-relaxed text-ivory/90">
                {thought(step.thinking)}
                {step.thinking.byRule && ' It played this one by its rule of thumb, with no model to ask.'}
              </p>
            </div>
          )}

          <div className="flex gap-2.5 pt-4">
            <button type="button" className="btn btn--fold flex-1" disabled={at === 0} onClick={() => go(at - 1)}>
              Back
            </button>
            <button type="button" className="btn btn--raise flex-1" disabled={at === last} onClick={() => go(at + 1)}>
              Next
            </button>
          </div>
        </div>
      </section>
    </div>
  )
}
