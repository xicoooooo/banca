import { afterEach, describe, expect, it } from 'vitest'
import { AMBIENCES, QUIET, THEMES, readAtmosphere } from './atmosphere'

/** A stand-in for the browser's storage, holding one value. */
function storing(value: string | null) {
  ;(globalThis as { localStorage?: unknown }).localStorage = { getItem: () => value }
}

afterEach(() => {
  delete (globalThis as { localStorage?: unknown }).localStorage
})

describe('the atmosphere a player has chosen', () => {
  it('is the quiet evening room until something is chosen', () => {
    storing(null)
    expect(readAtmosphere()).toEqual(QUIET)
    expect(QUIET).toEqual({ theme: 'evening', ambience: 'off' })
  })

  it('is read back as it was kept', () => {
    storing(JSON.stringify({ theme: 'candlelit', ambience: 'rain' }))
    expect(readAtmosphere()).toEqual({ theme: 'candlelit', ambience: 'rain' })
  })

  it('puts back the quiet room for anything it does not know', () => {
    storing(JSON.stringify({ theme: 'disco', ambience: 'foghorn' }))
    expect(readAtmosphere()).toEqual(QUIET)

    storing('not even json')
    expect(readAtmosphere()).toEqual(QUIET)
  })

  it('is the quiet room in a browser that keeps nothing', () => {
    expect(readAtmosphere()).toEqual(QUIET)
  })

  it('offers each light and each sound once, with the quiet ones first', () => {
    expect(THEMES[0].name).toBe('evening')
    expect(AMBIENCES[0].name).toBe('off')
    expect(new Set(THEMES.map((theme) => theme.name)).size).toBe(THEMES.length)
    expect(new Set(AMBIENCES.map((ambience) => ambience.name)).size).toBe(AMBIENCES.length)
  })
})
