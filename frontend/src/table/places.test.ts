import { describe, expect, it } from 'vitest'
import { ordinal } from './places'

describe('saying where someone finished', () => {
  it('says first, second, third and the rest the way they are written', () => {
    expect([1, 2, 3, 4, 5, 11, 12, 13, 21, 22, 23, 101, 111].map(ordinal)).toEqual([
      '1st', '2nd', '3rd', '4th', '5th', '11th', '12th', '13th', '21st', '22nd', '23rd', '101st', '111th',
    ])
  })
})
