// Mirrors the backend's BlackjackView. The wire format is documented in docs/protocol.md.

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
  phase: 'betting' | 'insurance' | 'player' | 'settled'
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
}

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

export type BlackjackClientMessage = { type: 'bet'; amount: number } | { type: 'act'; action: BlackjackAction } | { type: 'advise' }
