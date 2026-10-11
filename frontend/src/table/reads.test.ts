import { describe, expect, it } from 'vitest'
import { MOODS, NO_READS, afterRead, moodOf, readsInWords } from './reads'

describe('reading Banca', () => {
  it('knows each mood from the words the server tells it in', () => {
    expect(moodOf('In a steady mood: playing it straight, betting good hands.')).toBe('steady')
    expect(moodOf('In a patient mood: waiting for strong hands.')).toBe('patient')
    expect(moodOf('In a pressing mood: betting and raising.')).toBe('pressing')
    expect(moodOf('In a sly mood: hiding strong hands.')).toBe('sly')
  })

  it('names no mood for words that name none', () => {
    expect(moodOf('In a foul mood: sulking.')).toBeNull()
    expect(moodOf('Thought it over')).toBeNull()
    expect(moodOf(null)).toBeNull()
  })

  it('offers every mood once', () => {
    expect(MOODS.map((mood) => mood.name)).toEqual(['steady', 'patient', 'pressing', 'sly'])
  })

  it('counts a right read towards the run, and a wrong one ends it', () => {
    let reads = NO_READS
    for (const right of [true, true, true, false, true]) reads = afterRead(reads, right)

    expect(reads).toEqual({ total: 5, right: 4, streak: 1, best: 3 })
  })

  it('says the count in a few words', () => {
    expect(readsInWords(NO_READS)).toBe('No reads yet')
    expect(readsInWords({ total: 12, right: 7, streak: 3, best: 4 })).toBe('Right 7 of 12 · 3 in a row')
    expect(readsInWords({ total: 3, right: 1, streak: 1, best: 1 })).toBe('Right 1 of 3')
  })
})
