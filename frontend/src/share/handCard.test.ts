import { describe, expect, it } from 'vitest'
import type { PlayerView, TableView } from '../table/types'
import { handCardOf, seatLabel } from './handCard'

const player = (seat: number, name: string, cards: string[] | null, status: PlayerView['status'] = 'active'): PlayerView => ({
  seat,
  name,
  stack: 2000,
  committed: 0,
  status,
  cards,
})

/** A finished hand as the player in seat 0 saw it. */
function ended(over: Partial<TableView> & Pick<TableView, 'result'>): TableView {
  return {
    handNumber: 7,
    street: 'showdown',
    board: ['Ks', '7d', '2c', '9h', '3s'],
    pot: 800,
    buttonSeat: 0,
    smallBlind: 10,
    bigBlind: 20,
    yourSeat: 0,
    actorSeat: null,
    players: [player(0, 'You', ['Ah', 'Kd']), player(1, 'Banca', ['Qc', 'Qd'])],
    legal: null,
    ...over,
  }
}

describe('the card for a hand', () => {
  it('is not made while a hand is being played, or for one the player folded', () => {
    expect(handCardOf(ended({ result: null }), null)).toBeNull()
    const folded = ended({
      players: [player(0, 'You', ['Ah', 'Kd'], 'folded'), player(1, 'Banca', null)],
      result: { winnings: { 1: 60 }, showdown: {} },
    })
    expect(handCardOf(folded, null)).toBeNull()
  })

  it('says which hand beat which at a showdown the player won', () => {
    const card = handCardOf(ended({ result: { winnings: { 0: 800 }, showdown: { 0: 'two_pair', 1: 'pair' } } }), 'Fair enough.')!

    expect(card.tone).toBe('won')
    expect(card.headline).toBe('Two pair beats a pair')
    expect(card.detail).toBe('Won 800 chips')
    expect(card.quote).toBe('Fair enough.')
    expect(card.me).toEqual({ name: 'My hand', cards: ['Ah', 'Kd'], hand: 'two_pair', won: true })
    expect(card.others).toEqual([{ name: 'Banca', cards: ['Qc', 'Qd'], hand: 'pair', won: false }])
  })

  it('does not say a hand beat one of its own kind', () => {
    const card = handCardOf(ended({ result: { winnings: { 0: 800 }, showdown: { 0: 'flush', 1: 'flush' } } }), null)!

    expect(card.headline).toBe('A flush takes it')
  })

  it('names who folded when the hand was won without a showdown', () => {
    const alone = ended({
      street: 'flop',
      board: ['Ks', '7d', '2c'],
      players: [player(0, 'You', ['Ah', 'Kd']), player(1, 'Banca', null, 'folded')],
      result: { winnings: { 0: 240 }, showdown: {} },
    })
    expect(handCardOf(alone, null)).toMatchObject({ headline: 'Banca folded', detail: 'Won 240 chips', others: [] })

    const crowded = { ...alone, players: [...alone.players, player(2, 'Rui', null, 'folded')] }
    expect(handCardOf(crowded, null)!.headline).toBe('Everyone folded')
  })

  it('says what the player was beaten by, and who took the pot', () => {
    const card = handCardOf(ended({ result: { winnings: { 1: 1240 }, showdown: { 0: 'pair', 1: 'full_house' } } }), null)!

    expect(card.tone).toBe('lost')
    expect(card.headline).toBe('Beaten by a full house')
    expect(card.detail).toBe('Banca took 1,240 chips')
    expect(card.others[0].won).toBe(true)
  })

  it('calls a shared pot a split', () => {
    const card = handCardOf(ended({ result: { winnings: { 0: 400, 1: 400 }, showdown: { 0: 'straight', 1: 'straight' } } }), null)!

    expect(card).toMatchObject({ tone: 'split', headline: 'Split pot', detail: '400 chips back with a straight' })
  })

  it('shows the strongest hands first, and no more than there is room for', () => {
    const view = ended({
      players: [
        player(0, 'You', ['Ah', 'Kd']),
        player(1, 'Banca', ['Qc', 'Qd']),
        player(2, 'Rui', ['2h', '2d']),
        player(3, 'Marta', ['9c', '9d']),
        player(4, 'Ana', ['5c', '6c']),
        player(5, 'Gone', null, 'folded'),
      ],
      result: { winnings: { 0: 2000 }, showdown: { 0: 'straight_flush', 1: 'pair', 2: 'three_of_a_kind', 3: 'two_pair', 4: 'high_card' } },
    })
    const card = handCardOf(view, null)!

    expect(card.others.map((seat) => seat.name)).toEqual(['Rui', 'Marta', 'Banca'])
    expect(card.headline).toBe('A straight flush beats three of a kind')
  })

  it('labels a seat with whose it is and what they held', () => {
    expect(seatLabel({ name: 'Banca', cards: null, hand: 'full_house', won: true })).toBe('Banca · full house')
    expect(seatLabel({ name: 'My hand', cards: ['Ah', 'Kd'], hand: null, won: true })).toBe('My hand')
  })
})
