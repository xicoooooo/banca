// Mirrors the backend's RouletteView. The wire format is documented in docs/protocol.md.

import type { ChatLine, Phrase, RoomPlayer, TableListing } from '../casino/room'
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

/** The chips on one bet, everyone's together, and how many players put them there. */
export type CrowdSpot = { kind: Wager['kind']; number: number | null; amount: number; players: number }

/** A shared room as one player sees it. The room keeps the time; `msLeft` is how long its current phase has to run. */
export type RoomView = {
  room: string
  name: string
  /** True for a room a player opened for their own company, which is on no list. */
  byInvite: boolean
  /** At a private room: who spins the wheel, and whether that is this player. */
  host: string | null
  youHost: boolean
  roundNumber: number
  phase: 'betting' | 'spinning' | 'results'
  msLeft: number
  stack: number
  minBet: number
  maxInside: number
  maxOutside: number
  history: number[]
  pocket: number | null
  bets: Wager[]
  crowd: CrowdSpot[]
  players: RoomPlayer[]
  result: RouletteResult | null
}

export type RoomSummary = TableListing & { history: number[] }

export type RoomServerMessage =
  | ChipNotice
  | { type: 'state'; view: RoomView }
  | { type: 'chat_log'; lines: ChatLine[]; phrases: Phrase[] }
  | { type: 'chat'; line: ChatLine }
  | { type: 'trace'; event: ReadStep }
  | { type: 'read'; read: LayoutRead }
  | { type: 'error'; message: string }

export type RoomClientMessage =
  | { type: 'bets'; bets: Wager[] }
  // One of the room's set phrases by its id, or a message the player typed.
  | { type: 'chat'; say: string }
  | { type: 'chat'; text: string }
  | { type: 'analyse'; bets: Wager[] }
  | { type: 'start' }

export type RouletteClientMessage = { type: 'spin'; bets: Wager[] } | { type: 'analyse'; bets: Wager[] }
