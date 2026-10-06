// Mirrors the backend's TableView. The wire format is documented in docs/protocol.md.

import type { ChatLine, Phrase } from '../casino/room'
import type { ChipNotice } from '../player/types'

export type PlayerView = {
  seat: number
  name: string
  stack: number
  committed: number
  status: 'active' | 'folded' | 'all_in'
  cards: string[] | null
}

export type LegalView = {
  canFold: boolean
  canCheck: boolean
  canCall: boolean
  callCost: number
  canBet: boolean
  minBet: number
  canRaise: boolean
  minRaiseTo: number
  maxTo: number
}

export type ResultView = {
  winnings: Record<string, number>
  showdown: Record<string, string>
}

export type TableView = {
  handNumber: number
  street: 'preflop' | 'flop' | 'turn' | 'river' | 'showdown'
  board: string[]
  pot: number
  buttonSeat: number
  smallBlind: number
  bigBlind: number
  yourSeat: number
  actorSeat: number | null
  players: PlayerView[]
  legal: LegalView | null
  result: ResultView | null
}

export type TraceEvent = {
  kind: 'tool' | 'thought' | 'decision' | 'fallback'
  label: string
  /** Private to the opponent, so only present once the hand is over. */
  detail: string | null
}

export type ServerMessage =
  | ChipNotice
  | { type: 'state'; view: TableView }
  | { type: 'trace'; handNumber: number; event: TraceEvent }
  | { type: 'reveal'; handNumber: number; events: TraceEvent[] }
  | { type: 'error'; message: string }

export type ClientMessage =
  | { type: 'act'; action: 'fold' | 'check' | 'call' }
  | { type: 'act'; action: 'bet' | 'raise'; amount: number }
  | { type: 'next_hand' }

/** A play on a hand, which is said the same way at a table alone and at a shared one. */
export type ActMessage = Extract<ClientMessage, { type: 'act' }>

/** Someone with a seat at a shared table, whether or not they are in the hand being played. */
export type PokerSeat = { seat: number; name: string; you: boolean; inHand: boolean; away: boolean }

/** A shared poker table as one player sees it. `table` is the hand, in the shape a table alone has. */
export type PokerRoomView = {
  room: string
  name: string
  phase: 'waiting' | 'playing' | 'results'
  /** How long the table will wait for the player whose turn it is, or before the next hand. */
  msLeft: number
  yourTurn: boolean
  actor: string | null
  /** False for a player who sat down part way through a hand and is waiting for the next. */
  dealtIn: boolean
  table: TableView | null
  seats: PokerSeat[]
  seatsInAll: number
}

export type PokerRoomServerMessage =
  | ChipNotice
  | { type: 'state'; view: PokerRoomView }
  | { type: 'trace'; handNumber: number; event: TraceEvent }
  | { type: 'reveal'; handNumber: number; events: TraceEvent[] }
  | { type: 'chat_log'; lines: ChatLine[]; phrases: Phrase[] }
  | { type: 'chat'; line: ChatLine }
  | { type: 'error'; message: string; code?: string }

export type PokerRoomClientMessage =
  | { type: 'act'; action: 'fold' | 'check' | 'call' }
  | { type: 'act'; action: 'bet' | 'raise'; amount: number }
  | { type: 'chat'; say: string }
  | { type: 'chat'; text: string }
