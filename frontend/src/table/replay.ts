import { deriveEvents, type TableEvent } from './events'
import type { TableView, TraceEvent } from './types'

// A finished hand of Hold'em, played back a step at a time: every state the
// table was in, as this player saw it, with Banca's thinking set beside each
// decision it made. Nothing new is asked of the server. The states are the
// ones it sent while the hand was played, and Banca's thinking is what it
// revealed once the hand was over.

/** What Banca worked out before one decision, as far as it said. */
export type Thinking = {
  /** How often its hand would win, from 0 to 1, against the hands still in. */
  equity: number | null
  /** What a call had to win to pay, from 0 to 1, when there was something to call. */
  needed: number | null
  /** What it did, as it said it. */
  decision: string
  /** True when it played by its rule of thumb, with no model to ask. */
  byRule: boolean
}

export type ReplayStep = {
  /** The table just after this step, with Banca's cards face up where they are known. */
  view: TableView
  /** What happened, said in a line or two. */
  text: string
  /** Whose step it was, when it was one player's. */
  seat: number | null
  /** Banca's thinking, when the step was its decision. */
  thinking: Thinking | null
}

export type Replay = { handNumber: number; steps: ReplayStep[] }

const SUITS: Record<string, string> = { s: '♠', h: '♥', d: '♦', c: '♣' }
const cardName = (card: string) => `${card[0] === 'T' ? '10' : card[0]}${SUITS[card[1]] ?? ''}`
const chips = (amount: number) => amount.toLocaleString('en-US')
const handName = (hand: string) => hand.replaceAll('_', ' ')

/** What a tool told Banca, read back out of the line it was traced as: "get_hand_equity → {...}". */
function toolResult(event: TraceEvent, tool: string): Record<string, unknown> | null {
  const prefix = `${tool} → `
  if (!event.detail?.startsWith(prefix)) return null
  try {
    return JSON.parse(event.detail.slice(prefix.length)) as Record<string, unknown>
  } catch {
    return null
  }
}

/** Banca's revealed reasoning, cut into one piece for each decision, in order. */
export function thinkingOf(events: TraceEvent[]): Thinking[] {
  const turns: Thinking[] = []
  let equity: number | null = null
  let needed: number | null = null

  for (const event of events) {
    const equityFound = toolResult(event, 'get_hand_equity')
    if (equityFound && typeof equityFound.equity === 'number') equity = equityFound.equity
    const odds = toolResult(event, 'get_pot_odds')
    if (odds) needed = typeof odds.call_cost === 'number' && odds.call_cost > 0 && typeof odds.pot_odds === 'number' ? odds.pot_odds : null

    if (event.kind === 'decision' || event.kind === 'fallback') {
      const decision = event.label.replace(/^Decided to /, '').replace(/^Played by its rule of thumb and chose to /, '')
      turns.push({ equity, needed, decision, byRule: event.kind === 'fallback' })
      equity = null
      needed = null
    }
  }
  return turns
}

/** Banca's own cards, as its reasoning shows it looked at them. Null when it never had to look. */
export function bancaCardsOf(events: TraceEvent[]): string[] | null {
  for (const event of events) {
    const state = toolResult(event, 'get_game_state')
    if (state && Array.isArray(state.your_cards)) return state.your_cards as string[]
  }
  return null
}

/** The table with [cards] face up in [seat]'s place. */
function showing(view: TableView, seat: number | null, cards: string[] | null): TableView {
  if (seat === null || !cards) return view
  return { ...view, players: view.players.map((player) => (player.seat === seat ? { ...player, cards } : player)) }
}

/** The line for what happened between two states, in the order it happened. */
function said(events: TableEvent[], before: TableView, now: TableView, nameOf: (seat: number) => string): string {
  const lines: string[] = []
  // "You call", and "Banca calls".
  const does = (seat: number, verb: string) => `${nameOf(seat)} ${nameOf(seat) === 'You' ? verb : verb.replace(/^(\w+)/, (word) => (word === 'go' || word.endsWith('s') ? `${word}es` : `${word}s`))}`
  for (const event of events) {
    switch (event.type) {
      case 'bet': {
        const [verb, ...rest] = event.label.split(' ')
        const words: Record<string, string> = { Call: 'call', Raise: 'raise', Bet: 'bet', 'All-in': 'go all in for' }
        lines.push(`${does(event.seat, words[verb] ?? verb.toLowerCase())} ${rest.join(' ')}`.trim())
        break
      }
      case 'check':
        lines.push(does(event.seat, 'check'))
        break
      case 'fold':
        lines.push(does(event.seat, 'fold'))
        break
      case 'board': {
        const dealt = now.board.slice(before.board.length).map(cardName).join(' ')
        const street = now.board.length === 3 ? 'The flop' : now.board.length === 4 ? 'The turn' : 'The river'
        lines.push(`${street}: ${dealt}`)
        break
      }
      case 'showdown': {
        const shown = Object.entries(now.result?.showdown ?? {}).map(([seat, hand]) => `${nameOf(Number(seat))} with ${handName(hand)}`)
        lines.push(`Showdown: ${shown.join(', ')}`)
        break
      }
      case 'won': {
        const paid = event.seats.map((seat) => `${does(seat, 'take')} ${chips(event.amounts[seat] ?? 0)}`)
        lines.push(paid.join(', '))
        break
      }
    }
  }
  // Read as sentences: "Banca raises to 120. The flop: K♠ 7♦ 2♣."
  return lines.map((line) => line.charAt(0).toUpperCase() + line.slice(1)).join('. ')
}

/**
 * The hand played in [frames], the states the table was in from the first
 * this player saw, as steps to go through. [reasoning] is what Banca revealed
 * of its thinking once the hand was over, and [bancaSeat] where it sat.
 */
export function replayOf(frames: TableView[], reasoning: TraceEvent[], bancaSeat: number | null): Replay | null {
  const first = frames[0]
  if (!first || !frames.at(-1)?.result) return null

  const nameOf = (seat: number) =>
    seat === first.yourSeat ? 'You' : (first.players.find((player) => player.seat === seat)?.name ?? `Seat ${seat}`)
  const thinking = thinkingOf(reasoning)
  const cards = bancaCardsOf(reasoning)
  let decided = 0

  const blinds = first.players
    .filter((player) => player.committed > 0)
    .map((player) => `${nameOf(player.seat)} ${nameOf(player.seat) === 'You' ? 'post' : 'posts'} ${chips(player.committed)}`)
  const steps: ReplayStep[] = [
    { view: showing(first, bancaSeat, cards), text: blinds.length > 0 ? `The cards are dealt. ${blinds.join(', ')}` : 'The cards are dealt', seat: null, thinking: null },
  ]

  for (let index = 1; index < frames.length; index++) {
    const before = frames[index - 1]
    const now = frames[index]
    const events = deriveEvents(before, now)
    if (events.length === 0) continue

    const acted = events.find((event) => event.type === 'bet' || event.type === 'check' || event.type === 'fold')
    const seat = acted && 'seat' in acted ? acted.seat : null
    const isBanca = seat !== null && seat === bancaSeat
    steps.push({
      view: showing(now, bancaSeat, cards),
      text: said(events, before, now, nameOf),
      seat,
      thinking: isBanca ? (thinking[decided++] ?? null) : null,
    })
  }

  return { handNumber: first.handNumber, steps }
}
