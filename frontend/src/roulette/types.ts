// Mirrors the backend's RouletteView. The wire format is documented in docs/protocol.md.

import type { ChipNotice } from '../player/types'

export type PocketColor = 'green' | 'red' | 'black'

/**
 * A bet as it is written on the wire. `number` is the number for a straight
 * bet and which one for a dozen or column.
 */
export type Wager = {
  kind: 'straight' | 'dozen' | 'column' | 'red' | 'black' | 'even' | 'odd' | 'low' | 'high'
  number?: number | null
  amount: number
}

export type RouletteResult = {
  pocket: number
  color: PocketColor
  wagers: (Wager & { returned: number })[]
  staked: number
  net: number
  refilled: boolean
}

export type RouletteView = {
  roundNumber: number
  stack: number
  minBet: number
  maxInside: number
  maxOutside: number
  /** Where the ball has landed lately, newest first. */
  history: number[]
  result: RouletteResult | null
}

/** One step Banca took while reading a layout. Nothing in it is hidden: it sees only the player's own bets. */
export type ReadStep = { kind: 'tool' | 'thought' | 'decision' | 'fallback'; label: string; detail: string | null }

/** The figures behind a read, worked out on the server. Chances are shares of the wheel, from 0 to 1. */
export type LayoutFigures = {
  staked: number
  ahead: number
  level: number
  behind: number
  nothing: number
  best: number
  bestPockets: number[]
  /** What the layout comes to on average, per spin. Never above nought. */
  average: number
}

/** What Banca makes of a layout: "banca" when the model chose the words, "book" when the figures spoke for themselves. */
export type LayoutRead = { text: string; figures: LayoutFigures; source: 'banca' | 'book' }

export type RouletteServerMessage =
  | ChipNotice
  | { type: 'state'; view: RouletteView }
  | { type: 'trace'; event: ReadStep }
  | { type: 'read'; read: LayoutRead }
  | { type: 'error'; message: string }

export type RouletteClientMessage = { type: 'spin'; bets: Wager[] } | { type: 'analyse'; bets: Wager[] }
