// Mirrors the backend's BlackjackView. The wire format is documented in docs/protocol.md.

import type { ChatLine, Phrase } from '../casino/room'
import type { ChipNotice } from '../player/types'

export type Outcome = 'blackjack' | 'win' | 'push' | 'lose' | 'bust'

export type BlackjackHandView = {
  cards: string[]
  bet: number
  total: number
  soft: boolean
  status: 'playing' | 'waiting' | 'stood' | 'doubled' | 'bust' | 'blackjack'
  outcome: Outcome | null
  returned: number | null
}

export type BlackjackView = {
  roundNumber: number
  /** 'waiting' is only seen at a shared table: this player has finished, or sat the round out, and others are still playing. */
  phase: 'betting' | 'insurance' | 'player' | 'waiting' | 'settled'
  stack: number
  minBet: number
  maxBet: number
  lastBet: number | null
  dealer: { cards: (string | null)[]; total: number; soft: boolean } | null
  hands: BlackjackHandView[]
  activeHand: number | null
  legal: { bet: boolean; hit: boolean; stand: boolean; double: boolean; split: boolean; insurance: boolean }
  insuranceCost: number
  result: { net: number; insuranceReturned: number; refilled: boolean } | null
  /** Once settled: the player's decisions graded against the best play. Null when they had none to make. */
  review: RoundReview | null
}

/** One decision set against the best play. Values are per chip of the hand's bet. */
export type DecisionReview = {
  hand: number
  cards: string[]
  total: number
  soft: boolean
  dealer: string
  played: BlackjackAction
  best: BlackjackAction
  verdict: 'best' | 'slip' | 'mistake'
  playedValue: number
  bestValue: number
  /** Chips the play gave up on average. Nought when it was the best. */
  cost: number
  reason: string | null
  coach: 'followed' | 'ignored' | null
}

export type RoundReview = { decisions: DecisionReview[]; sound: number; cost: number }

export type BlackjackAction = 'hit' | 'stand' | 'double' | 'split' | 'insure' | 'decline_insurance'

/** One step the coach took. Unlike the poker opponent's, nothing in it is hidden: the coach sees only what the player sees. */
export type CoachStep = { kind: 'tool' | 'thought' | 'decision' | 'fallback'; label: string; detail: string | null }

/** What the coach advises, with what every open play is worth per chip bet, the best first. */
export type Advice = {
  action: BlackjackAction
  reason: string
  values: { action: BlackjackAction; value: number }[]
  /** "banca" when the model put it into words, "book" when the arithmetic spoke for itself. */
  source: 'banca' | 'book'
}

export type BlackjackServerMessage =
  | ChipNotice
  | { type: 'state'; view: BlackjackView }
  | { type: 'trace'; roundNumber: number; event: CoachStep }
  | { type: 'advice'; roundNumber: number; hand: number | null; advice: Advice }
  | { type: 'error'; message: string }

/** Someone at a shared table, as the others see them. */
export type TableSeat = { name: string; you: boolean; bet: number; hands: BlackjackHandView[]; acting: boolean; net: number | null }

/** A shared blackjack table as one player sees it. `you` is their own part in the round, in the shape a table alone has. */
export type BlackjackTableView = {
  room: string
  name: string
  /** True for a table a player opened for their own company, which is on no list. */
  byInvite: boolean
  /** At a private table: who asks for the cards, and whether that is this player. */
  host: string | null
  youHost: boolean
  roundNumber: number
  phase: 'betting' | 'insurance' | 'playing' | 'results'
  /** How long the table will wait in this phase, or for the player whose turn it is. */
  msLeft: number
  yourTurn: boolean
  actor: string | null
  you: BlackjackView
  seats: TableSeat[]
  seatsInAll: number
}

export type BlackjackTableServerMessage =
  | ChipNotice
  | { type: 'state'; view: BlackjackTableView }
  | { type: 'chat_log'; lines: ChatLine[]; phrases: Phrase[] }
  | { type: 'chat'; line: ChatLine }
  | { type: 'trace'; roundNumber: number; event: CoachStep }
  | { type: 'advice'; roundNumber: number; hand: number | null; advice: Advice }
  | { type: 'error'; message: string; code?: string }

export type BlackjackTableClientMessage =
  | { type: 'bet'; amount: number }
  | { type: 'start' }
  | { type: 'act'; action: BlackjackAction }
  | { type: 'advise' }
  | { type: 'chat'; say: string }
  | { type: 'chat'; text: string }

export type BlackjackClientMessage = { type: 'bet'; amount: number } | { type: 'act'; action: BlackjackAction } | { type: 'advise' }
