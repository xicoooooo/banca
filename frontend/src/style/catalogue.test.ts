import { describe, expect, it } from 'vitest'
import { CATALOGUE, DEFAULTS, allowed, asksFor, highestLeague, isUnlocked, itemsOf, type Progress } from './catalogue'

const newcomer: Progress = { level: 1, league: null, trophyLeagues: [], achievements: 0 }
const item = (kind: 'cards' | 'felt', id: string) => CATALOGUE.find((entry) => entry.kind === kind && entry.id === id)!
const open = (progress: Progress) => CATALOGUE.filter((entry) => isUnlocked(entry, progress)).map((entry) => `${entry.kind}:${entry.id}`)

describe('the catalogue', () => {
  it('gives a newcomer one of each and nothing more', () => {
    expect(open(newcomer)).toEqual(['cards:classic', 'felt:emerald'])
  })

  it('has no two items of a kind with the same id, and a plain one of each kind', () => {
    for (const kind of ['cards', 'felt'] as const) {
      const ids = itemsOf(kind).map((entry) => entry.id)
      expect(new Set(ids).size).toBe(ids.length)
      expect(item(kind, DEFAULTS[kind]).unlock.by).toBe('start')
    }
  })

  it('opens by level at exactly the level asked for', () => {
    expect(isUnlocked(item('cards', 'midnight'), { ...newcomer, level: 2 })).toBe(false)
    expect(isUnlocked(item('cards', 'midnight'), { ...newcomer, level: 3 })).toBe(true)
    expect(isUnlocked(item('felt', 'burgundy'), { ...newcomer, level: 11 })).toBe(false)
    expect(isUnlocked(item('felt', 'burgundy'), { ...newcomer, level: 30 })).toBe(true)
  })

  it('opens by achievements earned', () => {
    expect(isUnlocked(item('cards', 'ivory'), { ...newcomer, achievements: 6 })).toBe(false)
    expect(isUnlocked(item('cards', 'ivory'), { ...newcomer, achievements: 7 })).toBe(true)
  })

  it('opens by league for whoever is in it or above', () => {
    expect(isUnlocked(item('felt', 'slate'), { ...newcomer, league: 'Bronze' })).toBe(false)
    expect(isUnlocked(item('felt', 'slate'), { ...newcomer, league: 'Silver' })).toBe(true)
    expect(isUnlocked(item('cards', 'gilded'), { ...newcomer, league: 'Silver' })).toBe(false)
    expect(isUnlocked(item('cards', 'gilded'), { ...newcomer, league: 'Emerald' })).toBe(true)
  })

  it('keeps a league open for a player who has since gone down', () => {
    // A trophy in Silver sent them up to Gold, wherever a bad week has left them since.
    const fallen: Progress = { ...newcomer, league: 'Bronze', trophyLeagues: ['Silver'] }

    expect(highestLeague(fallen)).toBe(2)
    expect(isUnlocked(item('cards', 'gilded'), fallen)).toBe(true)
    expect(isUnlocked(item('felt', 'royal'), fallen)).toBe(false)
  })

  it('counts a trophy in the top league as the top league', () => {
    expect(highestLeague({ ...newcomer, league: 'Emerald', trophyLeagues: ['Emerald'] })).toBe(4)
  })

  it('keeps a guest out of everything the leagues give', () => {
    const veteran: Progress = { level: 40, league: null, trophyLeagues: [], achievements: 14 }

    expect(isUnlocked(item('felt', 'slate'), veteran)).toBe(false)
    expect(isUnlocked(item('cards', 'champion'), veteran)).toBe(false)
    expect(isUnlocked(item('cards', 'burgundy'), veteran)).toBe(true)
  })

  it('opens the champion back with any trophy at all', () => {
    expect(isUnlocked(item('cards', 'champion'), { ...newcomer, league: 'Silver', trophyLeagues: ['Bronze'] })).toBe(true)
  })

  it('says what each item asks for', () => {
    expect(asksFor(item('cards', 'midnight').unlock)).toBe('Level 3')
    expect(asksFor(item('cards', 'gilded').unlock)).toBe('Gold League')
    expect(asksFor(item('cards', 'ivory').unlock)).toBe('7 achievements')
    expect(asksFor(item('cards', 'champion').unlock)).toBe('Win a trophy')
  })

  it('falls back to the plain one for a choice that is not open, or is not a thing', () => {
    expect(allowed('felt', 'royal', newcomer)).toBe('emerald')
    expect(allowed('cards', 'nonsense', newcomer)).toBe('classic')
    expect(allowed('cards', 'midnight', { ...newcomer, level: 3 })).toBe('midnight')
    // A felt's id means nothing as a card back unless there is a back of that name too.
    expect(allowed('cards', 'slate', { ...newcomer, league: 'Emerald' })).toBe('classic')
  })
})
