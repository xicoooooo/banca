import { describe, expect, it } from 'vitest'
import type { RouletteResult } from '../roulette/types'
import type { TableView } from '../table/types'
import { RUN, pokerMoment, rouletteMoment, runAfter, runMoment } from './moments'

/** A finished hand as the player in seat 0 saw it. */
function hand(showdown: Record<string, string>, winnings: Record<string, number>): TableView {
  return {
    handNumber: 9,
    street: 'showdown',
    board: [],
    pot: 1240,
    buttonSeat: 0,
    smallBlind: 10,
    bigBlind: 20,
    yourSeat: 0,
    actorSeat: null,
    players: [],
    legal: null,
    result: { winnings, showdown },
  }
}

/** A spin with 20 chips staked on it in all. */
function spin(wagers: RouletteResult['wagers'], net = 340): RouletteResult {
  return { pocket: 17, color: 'black', wagers, staked: 20, net, refilled: false }
}

describe('what a table celebrates', () => {
  it('celebrates a rare hand the player won with', () => {
    expect(pokerMoment(hand({ 0: 'four_of_a_kind', 1: 'full_house' }, { 0: 1240 }))).toEqual({
      key: 'poker-9',
      title: 'Four of a kind',
      detail: 'You took 1,240',
    })
    expect(pokerMoment(hand({ 0: 'royal_flush', 1: 'flush' }, { 0: 400 }))?.title).toBe('Royal flush')
    expect(pokerMoment(hand({ 0: 'straight_flush', 1: 'flush' }, { 0: 400 }))?.title).toBe('Straight flush')
  })

  it('does not celebrate an ordinary hand, a rare one that lost, or someone else\'s', () => {
    expect(pokerMoment(hand({ 0: 'full_house', 1: 'pair' }, { 0: 1240 }))).toBeNull()
    expect(pokerMoment(hand({ 0: 'four_of_a_kind', 1: 'straight_flush' }, { 1: 1240 }))).toBeNull()
    expect(pokerMoment(hand({ 0: 'pair', 1: 'four_of_a_kind' }, { 1: 1240 }))).toBeNull()
    expect(pokerMoment({ ...hand({}, {}), result: null })).toBeNull()
    expect(pokerMoment(null)).toBeNull()
  })

  it('celebrates a single number that came in, with what it paid', () => {
    const result = spin([
      { kind: 'straight', number: 4, amount: 10, returned: 0 },
      { kind: 'straight', number: 17, amount: 10, returned: 360 },
    ])

    expect(rouletteMoment(result, 4)).toEqual({ key: 'roulette-4', title: 'Straight up', detail: '17 came in and paid 360' })
  })

  it('does not celebrate a spin where only the outside bets won, or no number hit', () => {
    expect(rouletteMoment(spin([{ kind: 'black', amount: 50, returned: 100 }]), 4)).toBeNull()
    expect(rouletteMoment(spin([{ kind: 'straight', number: 3, amount: 10, returned: 0 }]), 4)).toBeNull()
    expect(rouletteMoment(null, 4)).toBeNull()
  })

  it('does not celebrate a number that came in for someone who covered much of the wheel', () => {
    const hit: RouletteResult['wagers'] = [{ kind: 'straight', number: 17, amount: 10, returned: 360 }]

    expect(rouletteMoment({ ...spin(hit, 120), staked: 240 }, 4)).toBeNull()
    expect(rouletteMoment({ ...spin(hit, -10), staked: 370 }, 4)).toBeNull()
    // Three numbers backed and one right is eleven times the stake, which is still a moment.
    expect(rouletteMoment({ ...spin(hit, 330), staked: 30 }, 4)).not.toBeNull()
    expect(rouletteMoment({ ...spin(hit, 320), staked: 40 }, 4)).toBeNull()
  })

  it('counts a run of wins, which a loss or a push ends', () => {
    let run = 0
    for (const net of [100, 100, 100]) run = runAfter(run, net)
    expect(run).toBe(3)
    expect(runAfter(run, 0)).toBe(0)
    expect(runAfter(run, -100)).toBe(0)
  })

  it('celebrates the win that makes five, and not the ones before or after', () => {
    expect(runMoment(RUN - 1, 12)).toBeNull()
    expect(runMoment(RUN, 12)).toEqual({ key: 'blackjack-12', title: 'Five in a row', detail: 'Five rounds won one after another' })
    expect(runMoment(RUN + 1, 13)).toBeNull()
  })
})
