import { describe, expect, it } from 'vitest'
import { deriveBlackjackEvents } from './events'
import type { BlackjackHandView, BlackjackView } from './types'

function hand(overrides: Partial<BlackjackHandView> = {}): BlackjackHandView {
  return { cards: ['9s', '8c'], bet: 100, total: 17, soft: false, status: 'playing', outcome: null, returned: null, ...overrides }
}

/** A round in play: seventeen against a seven, the hole card down. */
function table(overrides: Partial<BlackjackView> = {}): BlackjackView {
  return {
    roundNumber: 1,
    phase: 'player',
    stack: 1900,
    minBet: 10,
    maxBet: 500,
    lastBet: 100,
    dealer: { cards: ['7d', null], total: 7, soft: false },
    hands: [hand()],
    activeHand: 0,
    legal: { bet: false, hit: true, stand: true, double: true, split: false, insurance: false },
    insuranceCost: 0,
    result: null,
    review: null,
    ...overrides,
  }
}

const waiting = table({ roundNumber: 0, phase: 'betting', stack: 2000, dealer: null, hands: [], activeHand: null, lastBet: null })
const types = (before: BlackjackView | null, now: BlackjackView) => deriveBlackjackEvents(before, now).map((event) => event.type)

describe('deriveBlackjackEvents', () => {
  it('says nothing about a table still waiting for its first bet', () => {
    expect(deriveBlackjackEvents(null, waiting)).toEqual([])
  })

  it('says nothing when the same view is seen twice', () => {
    const view = table()
    expect(deriveBlackjackEvents(view, view)).toEqual([])
  })

  it('starts a round when the bet is placed', () => {
    expect(deriveBlackjackEvents(waiting, table())).toEqual([{ type: 'round_started', bet: 100 }])
  })

  it('reads a hit as a card to the player', () => {
    const hit = table({ hands: [hand({ cards: ['9s', '8c', '2d'], total: 19 })] })
    expect(deriveBlackjackEvents(table(), hit)).toEqual([{ type: 'player_card' }])
  })

  it('reads a double as more chips and one card', () => {
    const before = table({ hands: [hand({ cards: ['5s', '6c'], total: 11 })] })
    const doubled = table({
      stack: 1800,
      phase: 'settled',
      dealer: { cards: ['7d', 'Kh'], total: 17, soft: false },
      hands: [hand({ cards: ['5s', '6c', 'Td'], bet: 200, total: 21, status: 'doubled', outcome: 'win', returned: 400 })],
      result: { net: 200, insuranceReturned: 0, refilled: false },
    })

    expect(deriveBlackjackEvents(before, doubled)).toEqual([
      { type: 'stake_added', amount: 100 },
      { type: 'player_card' },
      { type: 'settled', dealerDrew: 0, hands: [{ outcome: 'win', bet: 200, returned: 400 }], net: 200 },
    ])
  })

  it('reads a split as a second stake and new cards', () => {
    const pair = table({ hands: [hand({ cards: ['8s', '8c'], total: 16 })] })
    const split = table({
      stack: 1800,
      hands: [hand({ cards: ['8s', '3d'], total: 11 }), hand({ cards: ['8c', 'Tc'], total: 18, status: 'waiting' })],
    })

    expect(types(pair, split)).toEqual(['stake_added', 'player_card'])
  })

  it('counts the cards the dealer draws when the round settles', () => {
    const settled = table({
      phase: 'settled',
      stack: 2100,
      dealer: { cards: ['7d', '5h', '2c', '3d'], total: 17, soft: false },
      hands: [hand({ status: 'stood', total: 19, outcome: 'win', returned: 200 })],
      activeHand: null,
      result: { net: 100, insuranceReturned: 0, refilled: false },
    })

    expect(deriveBlackjackEvents(table(), settled)).toEqual([
      { type: 'settled', dealerDrew: 2, hands: [{ outcome: 'win', bet: 100, returned: 200 }], net: 100 },
    ])
  })

  it('reads insurance as chips leaving without landing on a hand', () => {
    const offered = table({ phase: 'insurance', dealer: { cards: ['Ad', null], total: 11, soft: true }, insuranceCost: 50 })
    const insured = table({ stack: 1850, dealer: { cards: ['Ad', null], total: 11, soft: true } })

    expect(deriveBlackjackEvents(offered, insured)).toEqual([{ type: 'insurance', amount: 50 }])
    expect(deriveBlackjackEvents(offered, table({ dealer: { cards: ['Ad', null], total: 11, soft: true } }))).toEqual([])
  })

  it('starts and settles in one step when a natural ends the round at the deal', () => {
    const natural = table({
      phase: 'settled',
      stack: 2150,
      dealer: { cards: ['7d', '9h'], total: 16, soft: false },
      hands: [hand({ cards: ['As', 'Kc'], total: 21, soft: true, status: 'blackjack', outcome: 'blackjack', returned: 250 })],
      activeHand: null,
      result: { net: 150, insuranceReturned: 0, refilled: false },
    })

    expect(types(waiting, natural)).toEqual(['round_started', 'settled'])
  })

  it('does not settle the same round twice', () => {
    const settled = table({ phase: 'settled', result: { net: -100, insuranceReturned: 0, refilled: false } })
    expect(deriveBlackjackEvents(settled, { ...settled })).toEqual([])
  })
})
