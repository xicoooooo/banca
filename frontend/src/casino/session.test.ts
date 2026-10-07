import { describe, expect, it } from 'vitest'
import { AWAY_ENDS_SITTING_MS, lengthInWords, reminderDue, sittingAt } from './session'

const MINUTE = 60_000
const start = 1_000_000_000

describe('sittingAt', () => {
  it('begins a sitting when there is none', () => {
    expect(sittingAt(null, start)).toEqual({ startedAt: start, lastSeen: start, remindedAt: null })
  })

  it('carries a sitting on while the player keeps being seen', () => {
    const sitting = sittingAt(sittingAt(null, start), start + 5 * MINUTE)
    expect(sitting.startedAt).toBe(start)
    expect(sitting.lastSeen).toBe(start + 5 * MINUTE)
  })

  it('starts afresh after long enough away, forgetting the last reminder', () => {
    const before = { startedAt: start, lastSeen: start + 50 * MINUTE, remindedAt: start + 30 * MINUTE }
    const back = start + 50 * MINUTE + AWAY_ENDS_SITTING_MS + 1
    expect(sittingAt(before, back)).toEqual({ startedAt: back, lastSeen: back, remindedAt: null })
  })

  it('keeps a sitting through a short break', () => {
    const before = { startedAt: start, lastSeen: start + 50 * MINUTE, remindedAt: null }
    expect(sittingAt(before, start + 55 * MINUTE).startedAt).toBe(start)
  })

  it('does not trust a sitting that begins in the future, as after a clock change', () => {
    const before = { startedAt: start + 90 * MINUTE, lastSeen: start + 90 * MINUTE, remindedAt: null }
    expect(sittingAt(before, start).startedAt).toBe(start)
  })
})

describe('reminderDue', () => {
  const sitting = { startedAt: start, lastSeen: start, remindedAt: null }

  it('is owed once the chosen length has passed, and not before', () => {
    expect(reminderDue(sitting, start + 59 * MINUTE, 60)).toBe(false)
    expect(reminderDue(sitting, start + 60 * MINUTE, 60)).toBe(true)
  })

  it('is owed again the same length after the last one', () => {
    const reminded = { ...sitting, remindedAt: start + 60 * MINUTE }
    expect(reminderDue(reminded, start + 90 * MINUTE, 60)).toBe(false)
    expect(reminderDue(reminded, start + 120 * MINUTE, 60)).toBe(true)
  })

  it('is never owed to a player who turned it off', () => {
    expect(reminderDue(sitting, start + 600 * MINUTE, 0)).toBe(false)
  })
})

describe('lengthInWords', () => {
  it('says a length the way a person would', () => {
    expect(lengthInWords(30 * MINUTE)).toBe('30 minutes')
    expect(lengthInWords(60 * MINUTE)).toBe('1 hour')
    expect(lengthInWords(61 * MINUTE)).toBe('1 hour 1 minute')
    expect(lengthInWords(150 * MINUTE)).toBe('2 hours 30 minutes')
    expect(lengthInWords(20_000)).toBe('1 minute')
  })
})
