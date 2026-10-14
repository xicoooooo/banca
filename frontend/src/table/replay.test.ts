import { describe, expect, it } from 'vitest'
import { bancaCardsOf, replayOf, thinkingOf } from './replay'
import type { PlayerView, TableView, TraceEvent } from './types'

const YOU = 0
const BANCA = 1

function frame(over: Partial<TableView>, you: Partial<PlayerView>, banca: Partial<PlayerView>): TableView {
  return {
    handNumber: 3,
    street: 'preflop',
    board: [],
    pot: 30,
    buttonSeat: YOU,
    smallBlind: 10,
    bigBlind: 20,
    yourSeat: YOU,
    actorSeat: YOU,
    players: [
      { seat: YOU, name: 'You', stack: 1990, committed: 10, status: 'active', cards: ['Ah', 'Kd'], ...you },
      { seat: BANCA, name: 'Banca', stack: 1980, committed: 20, status: 'active', cards: null, ...banca },
    ],
    legal: null,
    result: null,
    ...over,
  }
}

/** A hand in which the player calls, Banca checks the flop and then bets the turn, and the player folds. */
const frames: TableView[] = [
  frame({}, {}, {}),
  frame({ pot: 40, actorSeat: BANCA }, { committed: 20, stack: 1980 }, {}),
  frame({ street: 'flop', board: ['Ks', '7d', '2c'], pot: 40, actorSeat: BANCA }, { committed: 0, stack: 1980 }, { committed: 0 }),
  frame({ street: 'flop', board: ['Ks', '7d', '2c'], pot: 60, actorSeat: YOU }, { committed: 0, stack: 1980 }, { committed: 20, stack: 1960 }),
  frame(
    { street: 'flop', board: ['Ks', '7d', '2c'], pot: 60, actorSeat: null, result: { winnings: { [BANCA]: 60 }, showdown: {} } },
    { committed: 0, stack: 1980, status: 'folded' },
    { committed: 20, stack: 2020 },
  ),
]

const tool = (label: string, detail: string): TraceEvent => ({ kind: 'tool', label, detail })

const reasoning: TraceEvent[] = [
  { kind: 'thought', label: 'Settled on how to play', detail: 'In a sly mood: hiding strong hands.' },
  tool('Looked at the table', 'get_game_state → {"your_cards":["Qs","Qh"],"board":[],"pot":40}'),
  tool('Estimated its hand equity', 'get_hand_equity → {"equity":0.8,"against":"1 random hand"}'),
  tool('Worked out the pot odds', 'get_pot_odds → {"pot":40,"call_cost":0,"pot_odds":0.0,"note":"Nothing to call, checking is free."}'),
  { kind: 'decision', label: 'Decided to check', detail: null },
  tool('Estimated its hand equity', 'get_hand_equity → {"equity":0.74,"against":"1 random hand"}'),
  { kind: 'fallback', label: 'Played by its rule of thumb and chose to bet 20', detail: 'Banca could not be asked.' },
]

describe('replaying a hand', () => {
  it('cuts Banca\'s reasoning into its decisions, with what it worked out for each', () => {
    expect(thinkingOf(reasoning)).toEqual([
      { equity: 0.8, needed: null, decision: 'check', byRule: false },
      { equity: 0.74, needed: null, decision: 'bet 20', byRule: true },
    ])
  })

  it('says what a call had to win when there was something to call', () => {
    const facing = [tool('Worked out the pot odds', 'get_pot_odds → {"pot":300,"call_cost":100,"pot_odds":0.25}'), { kind: 'decision' as const, label: 'Decided to call', detail: null }]
    expect(thinkingOf(facing)[0].needed).toBe(0.25)
  })

  it('finds Banca\'s cards where it looked at them', () => {
    expect(bancaCardsOf(reasoning)).toEqual(['Qs', 'Qh'])
    expect(bancaCardsOf([])).toBeNull()
  })

  it('plays the hand back a step at a time, in words', () => {
    const replay = replayOf(frames, reasoning, BANCA)!

    expect(replay.handNumber).toBe(3)
    expect(replay.steps.map((step) => step.text)).toEqual([
      'The cards are dealt. You post 10, Banca posts 20',
      'You call 10',
      'Banca checks. The flop: K♠ 7♦ 2♣',
      'Banca bets 20',
      'You fold. Banca takes 60',
    ])
  })

  it('says a raise and an all-in the way they are said at a table', () => {
    const raised = frame({ pot: 80, actorSeat: BANCA }, { committed: 60, stack: 1940 }, {})
    const shoved = frame({ pot: 2060, actorSeat: YOU }, { committed: 60, stack: 1940 }, { committed: 2000, stack: 0, status: 'all_in' })
    const over: TableView = {
      ...shoved,
      actorSeat: null,
      result: { winnings: { [BANCA]: 2060 }, showdown: {} },
      players: shoved.players.map((player) => (player.seat === YOU ? { ...player, status: 'folded' as const } : player)),
    }
    const steps = replayOf([frame({}, {}, {}), raised, shoved, over], [], BANCA)!.steps

    expect(steps.map((step) => step.text).slice(1)).toEqual(['You raise to 60', 'Banca goes all in for 2000', 'You fold. Banca takes 2,060'])
  })

  it('sets Banca\'s thinking beside its own decisions and nobody else\'s', () => {
    const steps = replayOf(frames, reasoning, BANCA)!.steps

    expect(steps.map((step) => step.thinking?.decision ?? null)).toEqual([null, null, 'check', 'bet 20', null])
    expect(steps[2].seat).toBe(BANCA)
    expect(steps[1].seat).toBe(YOU)
  })

  it('shows Banca\'s cards all the way through, once it has revealed them', () => {
    const steps = replayOf(frames, reasoning, BANCA)!.steps

    expect(steps.every((step) => step.view.players.find((player) => player.seat === BANCA)?.cards?.join() === 'Qs,Qh')).toBe(true)
  })

  it('has nothing to play back before a hand is over', () => {
    expect(replayOf(frames.slice(0, 3), reasoning, BANCA)).toBeNull()
    expect(replayOf([], reasoning, BANCA)).toBeNull()
  })
})
