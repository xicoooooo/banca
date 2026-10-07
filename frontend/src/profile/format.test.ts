import { describe, expect, it } from 'vitest'
import { ago, areaPath, until, divergingBars, levelProgress, linePath, percent, plot, signed, toneOf } from './format'

describe('writing figures', () => {
  it('gives results a sign, and nothing a plain zero', () => {
    expect(signed(1200)).toBe('+1,200')
    expect(signed(-300)).toBe('−300')
    expect(signed(0)).toBe('0')
    expect(signed(-0.4)).toBe('0')
  })

  it('rounds shares to whole percentages', () => {
    expect(percent(0.4567)).toBe('46%')
    expect(percent(1)).toBe('100%')
  })

  it('tells a gain from a loss from neither', () => {
    expect([toneOf(5), toneOf(-5), toneOf(0)]).toEqual(['gain', 'loss', 'even'])
  })

  it('says how long ago in the largest unit that fits', () => {
    const now = new Date('2026-10-06T12:00:00Z')
    expect(ago('2026-10-06T11:59:40Z', now)).toBe('Just now')
    expect(ago('2026-10-06T11:15:00Z', now)).toBe('45 min ago')
    expect(ago('2026-10-06T07:00:00Z', now)).toBe('5 h ago')
    expect(ago('2026-10-03T12:00:00Z', now)).toBe('3 d ago')
    expect(ago('2026-09-20T12:00:00Z', now)).toBe('20 Sep')
  })

  it('says how long until something in hours and minutes', () => {
    const now = new Date('2026-10-06T12:00:00Z')
    expect(until('2026-10-06T15:12:00Z', now)).toBe('3h 12m')
    expect(until('2026-10-06T14:00:00Z', now)).toBe('2h')
    expect(until('2026-10-06T12:08:10Z', now)).toBe('9m')
    expect(until('2026-10-06T12:00:20Z', now)).toBe('under a minute')
    expect(until('2026-10-06T11:00:00Z', now)).toBe('now')
    expect(until('2026-10-12T00:00:00Z', now)).toBe('5d 12h')
    expect(until('2026-10-08T12:00:00Z', now)).toBe('2d')
  })

  it('measures progress through a level and stays within it', () => {
    expect(levelProgress(150, 100, 300)).toBe(0.25)
    expect(levelProgress(50, 100, 300)).toBe(0)
    expect(levelProgress(900, 100, 300)).toBe(1)
    expect(levelProgress(0, 0, 0)).toBe(0)
  })
})

describe('drawing charts', () => {
  it('spreads values across the width with the highest nearest the top', () => {
    const points = plot([2000, 2500, 1500], 100, 50)

    expect(points.map((p) => p.x)).toEqual([0, 50, 100])
    expect(points[1].y).toBeLessThan(points[0].y)
    expect(points[2].y).toBeGreaterThan(points[0].y)
    expect(points.every((p) => p.y > 0 && p.y < 50)).toBe(true)
  })

  it('draws a flat record through the middle rather than dividing by nothing', () => {
    expect(plot([2000, 2000], 100, 50).map((p) => p.y)).toEqual([25, 25])
    expect(plot([2000], 100, 50)).toEqual([{ x: 50, y: 25 }])
    expect(plot([], 100, 50)).toEqual([])
  })

  it('writes the line and the wash beneath it', () => {
    const points = [
      { x: 0, y: 10 },
      { x: 50, y: 20 },
    ]
    expect(linePath(points)).toBe('M0.0 10.0 L50.0 20.0')
    expect(areaPath(points, 40)).toBe('M0.0 10.0 L50.0 20.0 L50.0 40 L0.0 40 Z')
    expect(areaPath([points[0]], 40)).toBe('')
  })

  it('scales bars to the largest day, whichever way it went', () => {
    expect(divergingBars([100, -400, 0])).toEqual([0.25, -1, 0])
    expect(divergingBars([0, 0])).toEqual([0, 0])
  })
})
