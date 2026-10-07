import { useEffect, useState } from 'react'
import { GUIDES, stepToSay, type GuidedGame, type GuideStep } from './steps'

const key = (game: GuidedGame) => `banca.guide.${game}`

// Whether a guide has been seen is a convenience kept on this device. Without
// storage the guide is simply offered again, which a press on Skip answers.
function seen(game: GuidedGame): boolean {
  try {
    return window.localStorage.getItem(key(game)) === '1'
  } catch {
    return false
  }
}

function remember(game: GuidedGame, done: boolean) {
  try {
    if (done) window.localStorage.setItem(key(game), '1')
    else window.localStorage.removeItem(key(game))
  } catch {
    // Nothing to do: the guide still works, it just is not remembered.
  }
}

/** Puts a game's guide back, to be given again the next time its table is opened. */
export function replayGuide(game: GuidedGame) {
  remember(game, false)
}

/**
 * Where a round stands, as the table counts it: which round, how far through
 * it, and whether the table is idle, with nothing in play and a new round the
 * player's to start.
 */
export type Moment = { round: number; stage: number; idle: boolean; facts?: Record<string, boolean> }

export type Guiding = {
  step: GuideStep
  number: number
  inAll: number
  next: () => void
  skip: () => void
}

/**
 * Banca's walk through a first round, kept in step with the round itself.
 *
 * The table says where the round stands and this decides what is said. It
 * starts by itself the first time a game's table is opened on this device, and
 * finishing or skipping it is remembered there.
 */
export function useGuide(game: GuidedGame, moment: Moment | null): Guiding | null {
  const steps = GUIDES[game]
  const [index, setIndex] = useState(() => (seen(game) ? steps.length : 0))
  // The round on the table when the guide began. If the table was idle, whatever
  // it shows is left over from before and is not the round being taught: the
  // guide starts from the beginning and picks up when the next round does.
  const [from, setFrom] = useState<{ round: number; idle: boolean } | null>(null)

  const active = index < steps.length
  if (active && moment && from === null) setFrom({ round: moment.round, idle: moment.idle })

  const stage = !moment || !from ? null : from.idle && moment.round === from.round ? 0 : moment.stage
  const current = active && stage !== null ? stepToSay(steps, index, stage, moment?.facts) : index
  if (current !== index) setIndex(current)

  const finished = index >= steps.length
  useEffect(() => {
    if (finished) remember(game, true)
  }, [finished, game])

  if (stage === null || current >= steps.length) return null
  const step = steps[current]
  // A step for a stage the round has not reached is not said yet.
  if (step.stage !== stage) return null

  return {
    step,
    number: current + 1,
    inAll: steps.length,
    next: () => setIndex(current + 1),
    skip: () => setIndex(steps.length),
  }
}
