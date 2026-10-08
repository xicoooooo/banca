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

/** The numbers behind a piece of advice: how often the hand wins against random ones, the same marked down when someone has bet, and what a call must win to pay. */
export type PokerFigures = { equity: number; againstABet: number | null; opponents: number; potOdds: number; pot: number; callCost: number }

/** What the coach advises. `amount` is the total to have in front of you, for a bet or a raise. */
export type PokerAdvice = {
  action: 'fold' | 'check' | 'call' | 'bet' | 'raise'
  amount: number | null
  reason: string
  figures: PokerFigures
  /** "banca" when the model put it into words, "book" when the rule of thumb spoke for itself. */
  source: 'banca' | 'book'
}

export type ServerMessage =
  | ChipNotice
  | { type: 'state'; view: TableView }
  | { type: 'trace'; handNumber: number; event: TraceEvent }
  | { type: 'reveal'; handNumber: number; events: TraceEvent[] }
  | { type: 'coach_trace'; handNumber: number; event: TraceEvent }
  | { type: 'advice'; handNumber: number; advice: PokerAdvice }
  | { type: 'error'; message: string }

export type ClientMessage =
  | { type: 'act'; action: 'fold' | 'check' | 'call' }
  | { type: 'act'; action: 'bet' | 'raise'; amount: number }
  | { type: 'next_hand' }
  | { type: 'advise' }

/** A play on a hand, which is said the same way at a table alone and at a shared one. */
export type ActMessage = Extract<ClientMessage, { type: 'act' }>

/** Someone with a seat at a shared table, whether or not they are in the hand being played. */
export type PokerSeat = { seat: number; name: string; you: boolean; inHand: boolean; away: boolean }

/** A shared poker table as one player sees it. `table` is the hand, in the shape a table alone has. */
export type PokerRoomView = {
  room: string
  name: string
  /** True for a table a player opened for their own company, which is on no list. */
  byInvite: boolean
  /** At a private table: who starts the game, whether that is this player, and whether they have. */
  host: string | null
  youHost: boolean
  started: boolean
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
  | { type: 'coach_trace'; handNumber: number; event: TraceEvent }
  | { type: 'advice'; handNumber: number; advice: PokerAdvice }
  | { type: 'error'; message: string; code?: string }

export type PokerRoomClientMessage =
  | { type: 'advise' }
  | { type: 'start' }
  | { type: 'act'; action: 'fold' | 'check' | 'call' }
  | { type: 'act'; action: 'bet' | 'raise'; amount: number }
  | { type: 'chat'; say: string }
  | { type: 'chat'; text: string }
