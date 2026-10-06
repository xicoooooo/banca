import { describe, expect, it } from 'vitest'
import {
  NO_BETS,
  ROWS,
  WHEEL_ORDER,
  amountOn,
  betsFrom,
  angleOf,
  colorOf,
  firstTurn,
  turnFor,
  nextRotation,
  numbersOf,
  outlook,
  paysOf,
  place,
  spotOf,
  totalOf,
  undo,
  wagersOf,
  type Bets,
} from './layout'

const limits = { minBet: 10, maxInside: 100, maxOutside: 500, stack: 2000 }

function placing(...chips: [string, number][]): Bets {
  return chips.reduce((bets, [spot, chip]) => place(bets, spot, chip, limits), NO_BETS)
}

describe('the wheel and the felt', () => {
  it('has every number once on each', () => {
    const all = Array.from({ length: 37 }, (_, n) => n)
    expect([...WHEEL_ORDER].sort((a, b) => a - b)).toEqual(all)
    expect([...ROWS.flat()].sort((a, b) => a - b)).toEqual(all.slice(1))
  })

  it('lays the numbers out as a real table does', () => {
    expect(ROWS[0].slice(0, 3)).toEqual([3, 6, 9])
    expect(ROWS[2].slice(0, 3)).toEqual([1, 4, 7])
    expect(ROWS[0][11]).toBe(36)
  })

  it('colours the pockets', () => {
    expect([colorOf(0), colorOf(1), colorOf(2), colorOf(17), colorOf(32)]).toEqual(['green', 'red', 'black', 'black', 'red'])
  })
})

describe('what a bet covers and pays', () => {
  it('knows the numbers behind each spot', () => {
    expect(numbersOf('straight:17')).toEqual([17])
    expect(numbersOf('dozen:2')).toEqual([13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24])
    expect(numbersOf('column:2').slice(0, 3)).toEqual([2, 5, 8])
    expect(numbersOf('red')).toHaveLength(18)
    expect(numbersOf('even')).not.toContain(0)
    expect(numbersOf('high')[0]).toBe(19)
  })

  it('pays more the fewer numbers a bet covers', () => {
    expect([paysOf('straight:5'), paysOf('dozen:1'), paysOf('column:3'), paysOf('black')]).toEqual([35, 2, 2, 1])
  })

  it('names spots so they can be turned back into wagers', () => {
    expect(spotOf('straight', 0)).toBe('straight:0')
    expect(wagersOf(placing(['straight:0', 10], ['red', 50], ['red', 25]))).toEqual([
      { kind: 'straight', number: 0, amount: 10 },
      { kind: 'red', amount: 75 },
    ])
  })
})

describe('placing chips', () => {
  it('stacks chips on a spot and counts them all', () => {
    const bets = placing(['red', 50], ['red', 50], ['straight:7', 10])
    expect(amountOn(bets, 'red')).toBe(100)
    expect(totalOf(bets)).toBe(110)
  })

  it('stops at the limit for the kind of bet', () => {
    const number = placing(['straight:7', 100], ['straight:7', 25])
    expect(amountOn(number, 'straight:7')).toBe(100)

    const partly = placing(['straight:7', 50], ['straight:7', 25], ['straight:7', 50])
    expect(amountOn(partly, 'straight:7')).toBe(100)

    const box = placing(...Array.from({ length: 6 }, (): [string, number] => ['black', 100]))
    expect(amountOn(box, 'black')).toBe(500)
  })

  it('never lets the layout come to more than the player has', () => {
    const poor = { ...limits, stack: 60 }
    let bets = place(NO_BETS, 'red', 50, poor)
    bets = place(bets, 'black', 50, poor)
    expect(amountOn(bets, 'black')).toBe(10)
    expect(place(bets, 'even', 10, poor)).toBe(bets)
  })

  it('will not start a bet below the minimum', () => {
    const nearlyOut = { ...limits, stack: 55 }
    const bets = place(NO_BETS, 'red', 50, nearlyOut)
    expect(place(bets, 'black', 25, nearlyOut)).toBe(bets)
  })

  it('can be rebuilt from the wagers the server holds', () => {
    const bets = placing(['red', 50], ['red', 25], ['straight:7', 10], ['dozen:2', 50])
    expect(wagersOf(betsFrom(wagersOf(bets)))).toEqual(wagersOf(bets))
    expect(totalOf(betsFrom([{ kind: 'straight', number: 0, amount: 10 }]))).toBe(10)
  })

  it('takes back the last chip put down', () => {
    const bets = placing(['red', 50], ['straight:7', 10])
    expect(wagersOf(undo(bets))).toEqual([{ kind: 'red', amount: 50 }])
    expect(undo(NO_BETS)).toEqual(NO_BETS)
  })
})

describe('what a layout stands to do', () => {
  it('finds the best spin and how much of the wheel wins', () => {
    const single = outlook(placing(['straight:17', 10]))
    expect(single.best).toBe(350)
    expect(single.covered).toBeCloseTo(1 / 37)

    const red = outlook(placing(['red', 100]))
    expect(red.best).toBe(100)
    expect(red.covered).toBeCloseTo(18 / 37)
  })

  it('does not count a spin that only gives some chips back as a win', () => {
    // Red and black together: one always pays, and the spin still only breaks even.
    const both = outlook(placing(['red', 50], ['black', 50]))
    expect(both.best).toBe(0)
    expect(both.covered).toBe(0)
  })

  it('has nothing to say about an empty layout', () => {
    expect(outlook(NO_BETS)).toEqual({ best: 0, covered: 0 })
  })
})

describe('turning the wheel', () => {
  it('puts zero at the top to begin with and each pocket a step further round', () => {
    expect(angleOf(0)).toBe(0)
    expect(angleOf(32)).toBeCloseTo(360 / 37)
    expect(angleOf(26)).toBeCloseTo((36 * 360) / 37)
  })

  it('turns for the first spin after walking in, whatever round the room is on', () => {
    // Walking into round 7 while bets are open: no ball down yet.
    const arrived = firstTurn(7, null)
    expect(arrived.rotation).toBe(0)

    // Bets still open: nothing to turn for.
    expect(turnFor(arrived, 7, null)).toBe(arrived)

    // The same round is spun. This is the turn that used to be missed.
    const spun = turnFor(arrived, 7, 5)
    expect(spun.moved).toBe(true)
    expect(arrived.rotation - spun.rotation).toBeGreaterThanOrEqual(4 * 360)
    expect((((-spun.rotation - angleOf(5)) % 360) + 360) % 360).toBeCloseTo(0, 6)

    // Hearing about the same spin again does not turn it again.
    expect(turnFor(spun, 7, 5)).toBe(spun)

    // The next round opens, and then is spun.
    expect(turnFor(spun, 8, null)).toBe(spun)
    expect(turnFor(spun, 8, 17)).not.toBe(spun)
  })

  it('arriving after the ball has landed shows the wheel where it stopped, without a spin', () => {
    const arrived = firstTurn(7, 5)
    expect(arrived.moved).toBe(false)
    expect((((-arrived.rotation - angleOf(5)) % 360) + 360) % 360).toBeCloseTo(0, 6)
    expect(turnFor(arrived, 7, 5)).toBe(arrived)
  })

  it('a private table starts at rest and turns for its first spin', () => {
    const fresh = firstTurn(0, null)
    expect(turnFor(fresh, 1, 26).moved).toBe(true)
  })

  it('always spins on the same way, several turns, and stops on the pocket', () => {
    let rotation = 0
    for (const pocket of [17, 0, 32, 17, 5]) {
      const next = nextRotation(rotation, pocket)
      expect(rotation - next).toBeGreaterThanOrEqual(4 * 360)
      expect(rotation - next).toBeLessThan(6 * 360)
      // Stopped with the pocket at the top: turned back by exactly its angle.
      expect((((-next - angleOf(pocket)) % 360) + 360) % 360).toBeCloseTo(0, 6)
      rotation = next
    }
  })
})
