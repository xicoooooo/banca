import { useEffect } from 'react'
import type { Guiding } from './useGuide'

/** What sits at the foot of a table, under the felt. */
const BELOW = ['controls', 'chips', 'review']

/**
 * What Banca is saying, on a card laid over part of the table. It is not a
 * dialog: the table underneath stays live, because half of what it says is
 * "now you try". [quiet] puts it away while something is being shown that the
 * player should watch instead, such as the wheel turning.
 */
export function Guide({ guide, quiet = false }: { guide: Guiding | null; quiet?: boolean }) {
  const focus = guide && !quiet ? (guide.step.focus ?? null) : null

  // What the step is about is ringed on the table by a mark on the page itself.
  useEffect(() => {
    if (!focus) return
    document.documentElement.dataset.guideFocus = focus
    return () => {
      delete document.documentElement.dataset.guideFocus
    }
  }, [focus])

  if (!guide || quiet) return null
  const { step, number, inAll, next, skip } = guide
  const last = number === inAll
  // The card keeps out of the way of what it is talking about. Something to
  // read goes over the controls, which are not needed while reading; anything
  // about the controls, or waiting on them, goes over the top instead.
  const overControls = !step.waits && !BELOW.includes(step.focus ?? '')

  return (
    <aside className="guide" data-at={overControls ? 'bottom' : 'top'} aria-label="Banca's guide to the game">
      <div key={number} className="guide__card rise-in" role="status">
        <div className="flex items-center justify-between gap-3">
          <p className="label flex items-center gap-2 text-gold!">
            <span aria-hidden className="orb orb--idle" />
            {step.title}
          </p>
          <p className="label figure tracking-[0.1em]!">
            {number} of {inAll}
          </p>
        </div>

        <p className="pt-2 text-sm leading-relaxed text-ivory/90">{step.text}</p>

        <div className="flex items-center justify-between gap-3 pt-2.5">
          <button type="button" className="guide__skip label" onClick={skip}>
            {last ? '' : 'Skip the guide'}
          </button>
          {step.waits ? (
            <p className="label text-gold-bright!">Your move</p>
          ) : (
            <button type="button" className="btn btn--call px-5! py-2! text-sm" onClick={last ? skip : next}>
              {last ? 'Done' : 'Next'}
            </button>
          )}
        </div>
      </div>
    </aside>
  )
}
