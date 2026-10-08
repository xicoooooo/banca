import { describe, expect, it } from 'vitest'
import { shownCode, typedCode } from './tableCode'

describe('a table code', () => {
  it('is shown in capitals, in two groups', () => {
    expect(shownCode('k7x2m9')).toBe('K7X 2M9')
  })

  it('is left whole when it is not six characters', () => {
    expect(shownCode('emerald')).toBe('EMERALD')
  })

  it('is read back however it was typed', () => {
    expect(typedCode('K7X 2M9')).toBe('k7x2m9')
    expect(typedCode(' k7x-2m9 ')).toBe('k7x2m9')
    expect(typedCode('k7x2m9extra')).toBe('k7x2m9')
  })
})
