// The roulette table as arithmetic: where the numbers sit, what each bet
// covers, and what may be placed. Pure functions, so they can be tested
// without a browser. The server checks every bet again; this only keeps the
// player from building a layout it would refuse.

import type { PocketColor, Wager } from './types'

/** The pockets in the order they sit round a European wheel, clockwise from zero. */
export const WHEEL_ORDER = [
  0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7,
  28, 12, 35, 3, 26,
]

const RED = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36])

export function colorOf(pocket: number): PocketColor {
  return pocket === 0 ? 'green' : RED.has(pocket) ? 'red' : 'black'
}

/**
 * The numbers as they are laid out on the felt: three rows of twelve, the top
 * row holding 3, 6, 9 and so on, as on a real table.
 */
export const ROWS: number[][] = [0, 1, 2].map((row) => Array.from({ length: 12 }, (_, column) => 3 * (column + 1) - row))

/** A place on the layout where chips can go, named so it can be a key: "straight:17", "red", "dozen:2". */
export type Spot = string

export function spotOf(kind: Wager['kind'], number?: number): Spot {
  return number === undefined ? kind : `${kind}:${number}`
}

export function wagerOf(spot: Spot, amount: number): Wager {
  const [kind, number] = spot.split(':')
  return number === undefined
    ? { kind: kind as Wager['kind'], amount }
    : { kind: kind as Wager['kind'], number: Number(number), amount }
}

/** Whether a spot is on the numbers themselves, which have a lower limit than the boxes around them. */
export function isInside(spot: Spot): boolean {
  return spot.startsWith('straight')
}

/** The numbers a spot covers. */
export function numbersOf(spot: Spot): number[] {
  const [kind, value] = spot.split(':')
  const all = Array.from({ length: 36 }, (_, index) => index + 1)
  const which = Number(value)
  switch (kind) {
    case 'straight':
      return [which]
    case 'dozen':
      return all.filter((n) => Math.ceil(n / 12) === which)
    case 'column':
      return all.filter((n) => (n - 1) % 3 === which - 1)
    case 'red':
      return all.filter((n) => RED.has(n))
    case 'black':
      return all.filter((n) => !RED.has(n))
    case 'even':
      return all.filter((n) => n % 2 === 0)
    case 'odd':
      return all.filter((n) => n % 2 === 1)
    case 'low':
      return all.filter((n) => n <= 18)
    case 'high':
      return all.filter((n) => n >= 19)
    default:
      return []
  }
}

/** What a winning chip earns on top of coming back: 35 on a number, 2 on a dozen, 1 on red. */
export function paysOf(spot: Spot): number {
  return 36 / numbersOf(spot).length - 1
}

export type Limits = { minBet: number; maxInside: number; maxOutside: number; stack: number }

/** The chips on the layout: what is on each spot, and the order they went down in, so the last can be taken back. */
export type Bets = { placed: { spot: Spot; amount: number }[] }

export const NO_BETS: Bets = { placed: [] }

export function totalOf(bets: Bets): number {
  return bets.placed.reduce((sum, chip) => sum + chip.amount, 0)
}

export function amountOn(bets: Bets, spot: Spot): number {
  return bets.placed.filter((chip) => chip.spot === spot).reduce((sum, chip) => sum + chip.amount, 0)
}

/**
 * Puts a chip on a spot, or as much of it as the spot's limit and the
 * player's chips allow. Returns the same bets when nothing more can go there.
 */
export function place(bets: Bets, spot: Spot, chip: number, limits: Limits): Bets {
  const most = isInside(spot) ? limits.maxInside : limits.maxOutside
  const room = Math.min(most - amountOn(bets, spot), limits.stack - totalOf(bets))
  const amount = Math.min(chip, room)
  // A first chip smaller than the table's minimum would only be refused.
  if (amount <= 0 || amountOn(bets, spot) + amount < limits.minBet) return bets
  return { placed: [...bets.placed, { spot, amount }] }
}

export function undo(bets: Bets): Bets {
  return { placed: bets.placed.slice(0, -1) }
}

/** The layout as the server wants it: one wager for each spot with chips on it. */
export function wagersOf(bets: Bets): Wager[] {
  const spots = [...new Set(bets.placed.map((chip) => chip.spot))]
  return spots.map((spot) => wagerOf(spot, amountOn(bets, spot)))
}

/** The most a layout could win on one spin, and the share of the wheel on which it wins anything at all. */
export function outlook(bets: Bets): { best: number; covered: number } {
  const spots = [...new Set(bets.placed.map((chip) => chip.spot))]
  const total = totalOf(bets)
  let best = -total
  let winning = 0
  for (let pocket = 0; pocket <= 36; pocket++) {
    const returned = spots
      .filter((spot) => numbersOf(spot).includes(pocket))
      .reduce((sum, spot) => sum + amountOn(bets, spot) * (paysOf(spot) + 1), 0)
    best = Math.max(best, returned - total)
    if (returned > total) winning++
  }
  return { best: total === 0 ? 0 : best, covered: winning / 37 }
}

/**
 * How far the wheel must be turned, in degrees, for a pocket to sit under the
 * marker at the top. Pockets are drawn clockwise from zero.
 */
export function angleOf(pocket: number): number {
  return (WHEEL_ORDER.indexOf(pocket) * 360) / WHEEL_ORDER.length
}

/**
 * Where the wheel should stop for a new spin: several whole turns on from
 * where it rests now, ending with the pocket at the top.
 */
export function nextRotation(current: number, pocket: number, turns = 5): number {
  const target = -angleOf(pocket)
  const whole = Math.floor(current / 360) * 360
  let next = whole + target
  while (next > current - 360) next -= 360
  return next - 360 * (turns - 1)
}
