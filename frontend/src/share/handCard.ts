import type { TableView } from '../table/types'

// A finished poker hand as a picture worth sending to someone: what it says,
// worked out here from the table as the player saw it, and drawn elsewhere.

/** One player's place on the card. `cards` is null for a hand that was never shown. */
export type CardSeat = { name: string; cards: string[] | null; hand: string | null; won: boolean }

export type HandCard = {
  handNumber: number
  tone: 'won' | 'split' | 'lost'
  headline: string
  detail: string
  board: string[]
  me: CardSeat
  /** Those who showed their cards against the player, strongest first. Empty when nobody did. */
  others: CardSeat[]
  /** What Banca had to say about the hand, if it said anything. */
  quote: string | null
}

/** Hand categories, weakest first, as the server names them. */
const ORDER = ['high_card', 'pair', 'two_pair', 'three_of_a_kind', 'straight', 'flush', 'full_house', 'four_of_a_kind', 'straight_flush']

const NAMES: Record<string, string> = {
  high_card: 'high card',
  pair: 'a pair',
  two_pair: 'two pair',
  three_of_a_kind: 'three of a kind',
  straight: 'a straight',
  flush: 'a flush',
  full_house: 'a full house',
  four_of_a_kind: 'four of a kind',
  straight_flush: 'a straight flush',
}

const named = (hand: string) => NAMES[hand] ?? hand.replaceAll('_', ' ')
const capital = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)
const chips = (amount: number) => amount.toLocaleString('en-US')

/** How many opponents' hands a card has room to show. */
const SHOWN = 3

/**
 * The card for the hand in [view], or null when there is nothing to show
 * for it: the hand is still being played, the player was not in it, or they
 * folded, which nobody sends to a friend.
 */
export function handCardOf(view: TableView, said: string | null): HandCard | null {
  const result = view.result
  const mine = view.players.find((player) => player.seat === view.yourSeat)
  if (!result || !mine || mine.status === 'folded' || !mine.cards) return null

  const paid = (seat: number) => result.winnings[seat] ?? 0
  const winners = view.players.filter((player) => paid(player.seat) > 0)
  const showdown = Object.keys(result.showdown).length > 0
  const myHand = result.showdown[view.yourSeat] ?? null
  const iWon = paid(mine.seat) > 0
  const tone = !iWon ? 'lost' : winners.length > 1 ? 'split' : 'won'

  const others = view.players
    .filter((player) => player.seat !== mine.seat && result.showdown[player.seat] !== undefined)
    .map((player) => ({ name: player.name, cards: player.cards, hand: result.showdown[player.seat], won: paid(player.seat) > 0 }))
    .sort((a, b) => ORDER.indexOf(b.hand) - ORDER.indexOf(a.hand))
  const rivals = view.players.filter((player) => player.seat !== mine.seat)
  const best = others[0]
  const winner = winners.find((player) => player.seat !== mine.seat)

  let headline: string
  let detail: string
  if (tone === 'won') {
    headline = !showdown
      ? rivals.length === 1
        ? `${rivals[0].name} folded`
        : 'Everyone folded'
      : best && myHand && best.hand !== myHand
        ? `${capital(named(myHand))} beats ${named(best.hand)}`
        : `${capital(named(myHand ?? 'high_card'))} takes it`
    detail = `Won ${chips(paid(mine.seat))} chips`
  } else if (tone === 'split') {
    headline = 'Split pot'
    detail = `${chips(paid(mine.seat))} chips back${myHand ? ` with ${named(myHand)}` : ''}`
  } else {
    // A hand the player stayed in to the end and lost was lost at a showdown.
    const beatenBy = winner ? result.showdown[winner.seat] : undefined
    headline = beatenBy ? `Beaten by ${named(beatenBy)}` : 'Not this time'
    detail = winner ? `${winner.name} took ${chips(paid(winner.seat))} chips` : 'The pot went elsewhere'
  }

  return {
    handNumber: view.handNumber,
    tone,
    headline,
    detail,
    board: view.board,
    me: { name: 'My hand', cards: mine.cards, hand: myHand, won: iWon },
    others: others.slice(0, SHOWN),
    quote: said,
  }
}

/** A seat's label on the card: whose it is and what they held. */
export function seatLabel(seat: CardSeat): string {
  return seat.hand ? `${seat.name} · ${named(seat.hand).replace(/^a /, '')}` : seat.name
}
