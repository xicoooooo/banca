import { describe, expect, it } from 'vitest'
import { GUIDES, stepToSay, type GuidedGame } from './steps'

const games = Object.keys(GUIDES) as GuidedGame[]

describe('the guides', () => {
  it.each(games)('%s: stages only ever go forward, and it ends on a step with a button', (game) => {
    const steps = GUIDES[game]
    const stages = steps.map((step) => step.stage)
    expect(stages).toEqual([...stages].sort((a, b) => a - b))
    expect(steps.at(-1)!.waits).toBeFalsy()
  })

  it.each(games)('%s: every stage but the last ends by waiting for the player, so the round can move on', (game) => {
    const steps = GUIDES[game]
    const lastStage = steps.at(-1)!.stage
    for (let stage = 0; stage < lastStage; stage++) {
      const inStage = steps.filter((step) => step.stage === stage)
      expect(inStage.at(-1)!.waits).toBe(true)
    }
  })

  it.each(games)('%s: nothing said is too long for a card on a phone', (game) => {
    for (const step of GUIDES[game]) expect(step.text.length).toBeLessThanOrEqual(230)
  })
})

describe('stepToSay', () => {
  const steps = GUIDES.blackjack
  const titled = (index: number) => steps[index]?.title

  it('starts at the beginning and stays on a step until it is moved on', () => {
    expect(stepToSay(steps, 0, 0)).toBe(0)
    expect(stepToSay(steps, 1, 0)).toBe(1)
  })

  it('drops what the round has gone past', () => {
    // Dealt while still being told how to bet: on to the hand.
    expect(titled(stepToSay(steps, 1, 1, { deciding: true }))).toBe('Your hand')
    // A natural settles the round on the deal, so there was no hand to play.
    expect(titled(stepToSay(steps, 1, 2))).toBe('The dealer plays')
  })

  it('says what applies to the round and leaves out what does not', () => {
    expect(titled(stepToSay(steps, 2, 1, { insurance: true }))).toBe('Insurance')
    expect(titled(stepToSay(steps, 2, 1, { deciding: true }))).toBe('Your hand')
  })

  it('waits for a stage the round has not reached', () => {
    const waiting = stepToSay(steps, 2, 0)
    expect(steps[waiting].stage).toBe(1)
  })

  it('runs out when everything has been said or passed', () => {
    expect(stepToSay(steps, steps.length, 2)).toBe(steps.length)
    expect(stepToSay(steps, 0, 3)).toBe(steps.length)
  })
})
