// Mirrors the backend's BlackjackView. The wire format is documented in docs/protocol.md.

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

export type BlackjackServerMessage = { type: 'state'; view: BlackjackView } | { type: 'error'; message: string }

export type BlackjackClientMessage = { type: 'bet'; amount: number } | { type: 'act'; action: BlackjackAction }
