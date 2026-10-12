import { describe, expect, it } from 'vitest'
import { costOf, handName, markOf, scoreOf, type Drill, type DrillQuestion } from './drilling'

const sixteen: DrillQuestion = {
  cards: ['Th', '6d'],
  total: 16,
  soft: false,
  dealer: 'Kc',
  plays: [
    { action: 'hit', verdict: 'best', cost: 0 },
    { action: 'stand', verdict: 'slip', cost: 0.9 },
    { action: 'double', verdict: 'mistake', cost: 53.2 },
  ],
  best: 'hit',
  reason: 'A dealer showing a ten busts only 23% of the time.',
}

const eights: DrillQuestion = {
  cards: ['8h', '8d'],
  total: 16,
  soft: false,
  dealer: '9c',
  plays: [
    { action: 'hit', verdict: 'mistake', cost: 12 },
    { action: 'stand', verdict: 'mistake', cost: 15 },
    { action: 'double', verdict: 'mistake', cost: 60 },
    { action: 'split', verdict: 'best', cost: 0 },
  ],
  best: 'split',
  reason: 'Two hands starting from an 8 each do better than 16 played as one.',
}

describe('marking a drill', () => {
  it('marks the best play right, a near one close, a costly one wrong, and no play at all slow', () => {
    expect(markOf(sixteen, { played: 'hit', ms: 1200 })).toBe('right')
    expect(markOf(sixteen, { played: 'stand', ms: 1200 })).toBe('close')
    expect(markOf(sixteen, { played: 'double', ms: 1200 })).toBe('wrong')
    expect(markOf(sixteen, { played: null, ms: 8000 })).toBe('slow')
    expect(markOf(sixteen, { played: 'split', ms: 900 })).toBe('wrong')
  })

  it('counts what a play gave up, and running out of time as the worst there was', () => {
    expect(costOf(sixteen, { played: 'hit', ms: 1 })).toBe(0)
    expect(costOf(sixteen, { played: 'stand', ms: 1 })).toBe(0.9)
    expect(costOf(sixteen, { played: null, ms: 8000 })).toBe(53.2)
  })

  it('names a hand the way a player would say it', () => {
    expect(handName(sixteen)).toBe('Hard 16')
    expect(handName(eights)).toBe('Pair of 8s')
    expect(handName({ ...sixteen, cards: ['Ah', '7d'], total: 18, soft: true })).toBe('Soft 18')
    expect(handName({ ...eights, cards: ['Ah', 'Ad'], total: 12, soft: true })).toBe('Pair of aces')
    expect(handName({ ...eights, cards: ['Kh', 'Td'], total: 20 })).toBe('Pair of tens')
  })

  it('scores a drill: how many right, what it cost, how fast, and a word for it', () => {
    const drill: Drill = { questions: [sixteen, eights, sixteen, eights, sixteen], secondsEach: 8 }
    const score = scoreOf(drill, [
      { played: 'hit', ms: 1000 },
      { played: 'split', ms: 2000 },
      { played: 'stand', ms: 3000 },
      { played: 'hit', ms: 2000 },
      { played: null, ms: 8000 },
    ])

    expect(score).toEqual({ right: 2, close: 1, of: 5, cost: 66.1, seconds: 2, grade: 'Worth another go' })
  })

  it('has a word for every kind of drill', () => {
    const drill: Drill = { questions: Array(10).fill(sixteen), secondsEach: 8 }
    const grade = (right: number) =>
      scoreOf(drill, Array.from({ length: 10 }, (_, index) => ({ played: index < right ? ('hit' as const) : ('double' as const), ms: 1000 }))).grade

    expect(grade(10)).toBe('By the book')
    expect(grade(8)).toBe('Sharp')
    expect(grade(6)).toBe('Getting there')
    expect(grade(5)).toBe('Worth another go')
  })

  it('has no speed to speak of when nothing was answered', () => {
    expect(scoreOf({ questions: [sixteen], secondsEach: 8 }, [{ played: null, ms: 8000 }]).seconds).toBeNull()
  })
})
