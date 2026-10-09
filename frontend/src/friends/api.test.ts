import { describe, expect, it } from 'vitest'
import { shownFriendCode, typedFriendCode } from './api'

describe('a friend code', () => {
  it('is shown in capitals, in two groups of four', () => {
    expect(shownFriendCode('k7x2m9qf')).toBe('K7X2 M9QF')
  })

  it('is shown as it stands while it is still being typed', () => {
    expect(shownFriendCode('k7x')).toBe('K7X')
  })

  it('is read back however it was typed', () => {
    expect(typedFriendCode('K7X2 M9QF')).toBe('k7x2m9qf')
    expect(typedFriendCode('k7x2-m9qf-and-more')).toBe('k7x2m9qf')
  })
})
