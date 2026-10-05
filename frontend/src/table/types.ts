// Mirrors the backend's TableView. The wire format is documented in docs/protocol.md.

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

export type ServerMessage =
  | { type: 'state'; view: TableView }
  | { type: 'error'; message: string }

export type ClientMessage =
  | { type: 'act'; action: 'fold' | 'check' | 'call' }
  | { type: 'act'; action: 'bet' | 'raise'; amount: number }
  | { type: 'next_hand' }
