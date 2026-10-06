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

export type RouletteServerMessage = ChipNotice | { type: 'state'; view: RouletteView } | { type: 'error'; message: string }

export type RouletteClientMessage = { type: 'spin'; bets: Wager[] }
