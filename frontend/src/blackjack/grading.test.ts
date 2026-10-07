import { describe, expect, it } from 'vitest'
import { givenUp, summaryOf } from './grading'
import type { DecisionReview, RoundReview } from './types'

function decision(verdict: DecisionReview['verdict'], cost = 0): DecisionReview {
  return {
    hand: 0,
    cards: ['Ts', '6d'],
    total: 16,
    soft: false,
    dealer: 'Kh',
    played: 'stand',
    best: verdict === 'best' ? 'stand' : 'hit',
    verdict,
    playedValue: -0.54,
    bestValue: -0.5,
    cost,
    reason: verdict === 'best' ? null : 'A reason.',
    coach: null,
  }
}

function round(...decisions: DecisionReview[]): RoundReview {
  return {
    decisions,
    sound: decisions.filter((each) => each.verdict === 'best').length,
    cost: decisions.reduce((sum, each) => sum + each.cost, 0),
  }
}

describe('summaryOf', () => {
  it('says so when every decision was the best one', () => {
    expect(summaryOf(round(decision('best'), decision('best')))).toBe('Played by the book')
  })

  it('counts slips, and what they came to', () => {
    expect(summaryOf(round(decision('best'), decision('slip', 0.4)))).toBe('1 slip · 0.4 chips given up')
    expect(summaryOf(round(decision('slip', 1), decision('slip', 1.5)))).toBe('2 slips · 2.5 chips given up')
  })

  it('names mistakes ahead of slips, and both when there are both', () => {
    expect(summaryOf(round(decision('mistake', 12.3), decision('slip', 0.4)))).toBe('1 mistake, 1 slip · 13 chips given up')
    expect(summaryOf(round(decision('mistake', 6), decision('mistake', 7)))).toBe('2 mistakes · 13 chips given up')
  })
})

describe('givenUp', () => {
  it('keeps a decimal only for small amounts', () => {
    expect(givenUp(0.4)).toBe('0.4')
    expect(givenUp(9.5)).toBe('9.5')
    expect(givenUp(1234.6)).toBe('1,235')
  })
})
