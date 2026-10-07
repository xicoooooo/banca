export type GuidedGame = 'poker' | 'blackjack' | 'roulette'

/**
 * One thing Banca says while walking a player through their first round.
 *
 * A round moves through stages, which each table counts for itself, and a
 * step belongs to one of them. It is said only while the round is at that
 * stage: a step the round has gone past is dropped, and one it has not reached
 * waits.
 */
export type GuideStep = {
  stage: number
  title: string
  text: string
  /** What on the table the step is about, to be ringed while it is said. */
  focus?: string
  /** True when the step is waiting for the player to do something. It has no Next: playing on is what moves it. */
  waits?: boolean
  /** Said only when this fact about the round holds, such as insurance being on offer. */
  onlyIf?: string
}

// Blackjack: 0 before the cards, 1 while the hand is played, 2 once it is settled.
const BLACKJACK: GuideStep[] = [
  {
    stage: 0,
    title: 'Welcome to blackjack',
    text: 'I am Banca. Here you play against the dealer: get closer to 21 than they do, without going over. The chips are play money, free and worth nothing, so this round costs you nothing real.',
  },
  { stage: 0, title: 'Place a bet', text: 'Tap the chips to build a bet, then press Deal.', focus: 'controls', waits: true },
  {
    stage: 1,
    title: 'Insurance',
    text: 'The dealer shows an ace, so you are offered a side bet that they have blackjack. It loses more than it pays. Say no thanks.',
    focus: 'controls',
    waits: true,
    onlyIf: 'insurance',
  },
  {
    stage: 1,
    title: 'Your hand',
    text: 'These are your cards, with their total underneath. Faces count ten and an ace counts one or eleven. The dealer has one card showing and one face down.',
    focus: 'hand',
    onlyIf: 'deciding',
  },
  {
    stage: 1,
    title: 'Your choices',
    text: 'Hit takes another card. Stand keeps what you have. Double doubles your bet for exactly one more card. Go past 21 and you bust, and the bet is lost.',
    focus: 'controls',
    onlyIf: 'deciding',
  },
  {
    stage: 1,
    title: 'Ask me',
    text: 'Not sure? Press Ask Banca and I will say what I would do, and why. Then play the hand your way.',
    focus: 'banca',
    waits: true,
    onlyIf: 'deciding',
  },
  {
    stage: 2,
    title: 'The dealer plays',
    text: 'The dealer must draw until they have 17 or more. Then the higher hand wins. An ace with a ten on the deal is a blackjack, and pays three to two.',
  },
  {
    stage: 2,
    title: 'How you played',
    text: 'Whenever a round gives you choices to make, I grade them afterwards: the choices, not how the cards fell. The line above your bet opens it. That is the game. Good luck.',
    focus: 'review',
  },
]

// Poker: 0 while a hand is being played, 1 once it is over.
const POKER: GuideStep[] = [
  {
    stage: 0,
    title: "Welcome to Texas Hold'em",
    text: 'I am Banca, and at this table I am your opponent. We each get two cards of our own, five more are shared in the middle, and the best five-card hand takes the pot. The chips are play money, free and worth nothing.',
  },
  { stage: 0, title: 'Your cards', text: 'These two are yours alone. I cannot see them, and you cannot see mine.', focus: 'hand' },
  {
    stage: 0,
    title: 'The pot',
    text: 'The pot is what we have both put in. Every hand starts with two forced bets, the blinds of 10 and 20, so there is always something to win.',
    focus: 'pot',
  },
  {
    stage: 0,
    title: 'Your turn',
    text: 'Fold gives the hand up. Check or Call stays in for the price shown. Bet or Raise puts more in: slide to choose how much.',
    focus: 'controls',
  },
  {
    stage: 0,
    title: 'Watch me think',
    text: 'On my turn you will see me work it out, step by step. Once the hand is over you can open my full reasoning.',
    focus: 'opponent',
  },
  {
    stage: 0,
    title: 'Ask the coach',
    text: 'Stuck? The coach is a second me that sees only your cards. Ask it, then play the hand out.',
    focus: 'banca',
    waits: true,
  },
  {
    stage: 1,
    title: 'That is a hand',
    text: 'The shared cards come in three parts, the flop, the turn and the river, with betting after each. If nobody folds, the cards are shown and the best hand wins. Press Next hand to play on.',
  },
]

// Roulette: 0 while chips go down and the wheel turns, 1 once the ball has landed.
const ROULETTE: GuideStep[] = [
  {
    stage: 0,
    title: 'Welcome to roulette',
    text: 'I am Banca. One wheel, 37 pockets, numbered 0 to 36. You put chips on the layout, the ball lands, and every bet that covers the number is paid. The chips are play money, free and worth nothing.',
  },
  { stage: 0, title: 'Chips', text: 'Pick a chip value here. Each press on the layout puts one down, and Undo takes the last one back.', focus: 'chips' },
  {
    stage: 0,
    title: 'The layout',
    text: 'A single number pays 35 to 1. Red or black, odd or even, and the dozens cover more numbers and pay less. Covering more wins more often, but never more in the long run.',
    focus: 'layout',
  },
  { stage: 0, title: 'Ask, then spin', text: 'With chips down, ask me what your bets are likely to do. Then press Spin.', focus: 'banca', waits: true },
  {
    stage: 1,
    title: 'The house edge',
    text: 'The zero is the house: on every bet, whatever you choose, it keeps one part in 37. No system changes that, so play it for fun.',
  },
]

export const GUIDES: Record<GuidedGame, GuideStep[]> = { poker: POKER, blackjack: BLACKJACK, roulette: ROULETTE }

/**
 * Which step is to be said, starting from [index], for a round at [stage].
 * Steps the round has passed, or that do not apply to it, are skipped. The
 * answer is the number of steps when there is nothing left to say, and the
 * step it names may still be waiting for its stage.
 */
export function stepToSay(steps: GuideStep[], index: number, stage: number, facts: Record<string, boolean> = {}): number {
  let next = index
  while (next < steps.length) {
    const step = steps[next]
    const passed = step.stage < stage
    const doesNotApply = step.stage === stage && step.onlyIf !== undefined && !facts[step.onlyIf]
    if (!passed && !doesNotApply) break
    next++
  }
  return next
}
