import { describe, expect, it } from 'vitest'
import { deriveEvents } from './events'
import type { PlayerView, TableView } from './types'

function player(seat: number, overrides: Partial<PlayerView> = {}): PlayerView {
  return { seat, name: seat === 0 ? 'You' : 'Banca', stack: 1980, committed: 0, status: 'active', cards: null, ...overrides }
}

/** A heads-up table on the flop with nothing bet, you to act. */
function table(overrides: Partial<TableView> = {}): TableView {
  return {
    handNumber: 1,
    street: 'flop',
    board: ['Ah', 'Td', 'Ks'],
    pot: 40,
    buttonSeat: 0,
    smallBlind: 10,
    bigBlind: 20,
    yourSeat: 0,
    actorSeat: 0,
    players: [player(0), player(1)],
    legal: null,
    result: null,
    ...overrides,
  }
}

const types = (before: TableView | null, now: TableView) => deriveEvents(before, now).map((event) => event.type)

describe('deriveEvents', () => {
  it('starts a hand with the blinds going in', () => {
    const dealt = table({
      street: 'preflop',
      board: [],
      pot: 30,
      players: [player(0, { committed: 10 }), player(1, { committed: 20 })],
    })

    expect(deriveEvents(null, dealt)).toEqual([
      { type: 'hand_started' },
      { type: 'blind', seat: 0, amount: 10 },
      { type: 'blind', seat: 1, amount: 20 },
    ])
  })

  it('treats a new hand number as a new hand whatever came before', () => {
    const finished = table({ handNumber: 1, actorSeat: null })
    const next = table({ handNumber: 2, street: 'preflop', board: [], pot: 30 })

    expect(types(finished, next)[0]).toBe('hand_started')
  })

  it('says nothing when the same view is seen twice', () => {
    // Without this a repeated view would read as the actor checking.
    const view = table()
    expect(deriveEvents(view, view)).toEqual([])
  })

  it('reads an opening bet', () => {
    const before = table()
    const now = table({ pot: 100, actorSeat: 1, players: [player(0, { committed: 60, stack: 1920 }), player(1)] })

    expect(deriveEvents(before, now)).toEqual([{ type: 'bet', seat: 0, amount: 60, label: 'Bet 60' }])
  })

  it('tells a call from a raise by what was owed', () => {
    const facingBet = table({ pot: 100, actorSeat: 1, players: [player(0, { committed: 60 }), player(1)] })

    const called = table({ pot: 160, actorSeat: null, players: [player(0, { committed: 60 }), player(1, { committed: 60 })] })
    expect(deriveEvents(facingBet, called)).toEqual([{ type: 'bet', seat: 1, amount: 60, label: 'Call 60' }])

    const raised = table({ pot: 280, actorSeat: 0, players: [player(0, { committed: 60 }), player(1, { committed: 180 })] })
    expect(deriveEvents(facingBet, raised)).toEqual([{ type: 'bet', seat: 1, amount: 180, label: 'Raise to 180' }])
  })

  it('names an all-in for what it is', () => {
    const before = table()
    const now = table({
      pot: 2020,
      actorSeat: 1,
      players: [player(0, { committed: 1980, stack: 0, status: 'all_in' }), player(1)],
    })

    expect(deriveEvents(before, now)[0]).toEqual({ type: 'bet', seat: 0, amount: 1980, label: 'All-in 1980' })
  })

  it('reads a check when the actor paid nothing', () => {
    const before = table()
    const now = table({ actorSeat: 1 })

    expect(deriveEvents(before, now)).toEqual([{ type: 'check', seat: 0 }])
  })

  it('reads a fold and pays the other player without a showdown', () => {
    const facingBet = table({ pot: 100, actorSeat: 1, players: [player(0, { committed: 60 }), player(1)] })
    const folded = table({
      pot: 100,
      actorSeat: null,
      players: [player(0, { committed: 60 }), player(1, { status: 'folded' })],
      result: { winnings: { '0': 100 }, showdown: {} },
    })

    expect(deriveEvents(facingBet, folded)).toEqual([
      { type: 'fold', seat: 1 },
      { type: 'collect', seats: [0], amounts: { 0: 60 } },
      { type: 'won', seats: [0], amounts: { 0: 100 } },
    ])
  })

  it('sweeps the bets in and deals when a call ends the street', () => {
    // The call and the new street arrive in one state, with bets already cleared.
    const facingBet = table({ pot: 100, actorSeat: 1, players: [player(0, { committed: 60 }), player(1)] })
    const turn = table({ street: 'turn', board: ['Ah', 'Td', 'Ks', '2c'], pot: 160, actorSeat: 1 })

    expect(deriveEvents(facingBet, turn)).toEqual([
      { type: 'bet', seat: 1, amount: 60, label: 'Call 60' },
      { type: 'collect', seats: [0, 1], amounts: { 0: 60, 1: 60 } },
      { type: 'board', cards: 1 },
    ])
  })

  it('deals without a sweep when the street was checked through', () => {
    const before = table({ actorSeat: 1 })
    const turn = table({ street: 'turn', board: ['Ah', 'Td', 'Ks', '2c'], actorSeat: 1 })

    expect(types(before, turn)).toEqual(['check', 'board'])
  })

  it('deals the whole board at once when everyone is all-in', () => {
    const preflop = table({
      street: 'preflop',
      board: [],
      pot: 2020,
      actorSeat: 1,
      players: [player(0, { committed: 2000, stack: 0, status: 'all_in' }), player(1, { committed: 20 })],
    })
    const runOut = table({
      street: 'showdown',
      board: ['Ah', 'Td', 'Ks', '2c', '9d'],
      pot: 4000,
      actorSeat: null,
      players: [player(0, { stack: 4000, status: 'all_in' }), player(1, { stack: 0, status: 'all_in' })],
      result: { winnings: { '0': 4000 }, showdown: { '0': 'pair', '1': 'high_card' } },
    })

    expect(types(preflop, runOut)).toEqual(['bet', 'collect', 'board', 'showdown', 'won'])
    expect(deriveEvents(preflop, runOut)[2]).toEqual({ type: 'board', cards: 5 })
  })

  it('reports a showdown and both winners of a split pot', () => {
    const river = table({ street: 'river', board: ['Ah', 'Td', 'Ks', '2c', '9d'], actorSeat: 0 })
    const split = table({
      street: 'showdown',
      board: ['Ah', 'Td', 'Ks', '2c', '9d'],
      actorSeat: null,
      result: { winnings: { '0': 20, '1': 20 }, showdown: { '0': 'straight', '1': 'straight' } },
    })

    expect(deriveEvents(river, split)).toEqual([
      { type: 'check', seat: 0 },
      { type: 'showdown' },
      { type: 'won', seats: [0, 1], amounts: { 0: 20, 1: 20 } },
    ])
  })
})
