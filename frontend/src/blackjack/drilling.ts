// A blackjack drill: some hands to decide on against a clock, each play
// already graded by the server with the same arithmetic as the coach. Nothing
// rides on it, so the answers come with the questions and are told at once.

export type DrillAction = 'hit' | 'stand' | 'double' | 'split'

/** One way a hand could be played. `cost` is chips given up on average for every hundred staked. */
export type DrillPlay = { action: DrillAction; verdict: 'best' | 'slip' | 'mistake'; cost: number }

export type DrillQuestion = {
  cards: string[]
  total: number
  soft: boolean
  dealer: string
  plays: DrillPlay[]
  best: DrillAction
  reason: string
}

export type Drill = { questions: DrillQuestion[]; secondsEach: number }

/** What the player did with one hand: a play, or nothing before the clock ran out. `ms` is how long they took. */
export type Answer = { played: DrillAction | null; ms: number }

export const ACTION_NAMES: Record<DrillAction, string> = { hit: 'Hit', stand: 'Stand', double: 'Double', split: 'Split' }

/** How an answer is marked: right, close (a play that gives up very little), wrong, or too slow. */
export type Mark = 'right' | 'close' | 'wrong' | 'slow'

export function markOf(question: DrillQuestion, answer: Answer): Mark {
  if (answer.played === null) return 'slow'
  const play = question.plays.find((candidate) => candidate.action === answer.played)
  if (!play) return 'wrong'
  return play.verdict === 'best' ? 'right' : play.verdict === 'slip' ? 'close' : 'wrong'
}

/** What an answer gave up, on a hundred staked. Running out of time is counted as the worst play there was. */
export function costOf(question: DrillQuestion, answer: Answer): number {
  const played = question.plays.find((candidate) => candidate.action === answer.played)
  return played ? played.cost : Math.max(...question.plays.map((play) => play.cost))
}

/** What a hand is called: "Pair of 8s", "Soft 18", "Hard 16". */
export function handName(question: DrillQuestion): string {
  const [first, second] = question.cards.map((card) => card[0])
  const tens = 'TJQK'
  const pair = first === second || (tens.includes(first) && tens.includes(second))
  if (pair && question.plays.some((play) => play.action === 'split')) {
    return `Pair of ${first === 'A' ? 'aces' : tens.includes(first) ? 'tens' : `${first}s`}`
  }
  return `${question.soft ? 'Soft' : 'Hard'} ${question.total}`
}

/** How a finished drill went. */
export type Score = {
  right: number
  close: number
  of: number
  /** Chips given up over the whole drill, had a hundred been staked on every hand. */
  cost: number
  /** How long a decision took on average, in seconds, over the hands that were answered. */
  seconds: number | null
  /** A word for it. */
  grade: string
}

export function scoreOf(drill: Drill, answers: Answer[]): Score {
  const marks = drill.questions.map((question, index) => markOf(question, answers[index] ?? { played: null, ms: 0 }))
  const right = marks.filter((mark) => mark === 'right').length
  const answered = answers.filter((answer) => answer.played !== null)
  const of = drill.questions.length
  const cost = drill.questions.reduce((sum, question, index) => sum + costOf(question, answers[index] ?? { played: null, ms: 0 }), 0)

  return {
    right,
    close: marks.filter((mark) => mark === 'close').length,
    of,
    cost: Math.round(cost * 10) / 10,
    seconds: answered.length > 0 ? Math.round(answered.reduce((sum, answer) => sum + answer.ms, 0) / answered.length / 100) / 10 : null,
    grade: right === of ? 'By the book' : right >= of * 0.8 ? 'Sharp' : right >= of * 0.6 ? 'Getting there' : 'Worth another go',
  }
}

const KEY = 'banca.drill.best'

/** The most hands got right in one drill on this device, or null before the first. */
export function bestDrill(): number | null {
  try {
    const kept = Number(localStorage.getItem(KEY))
    return Number.isInteger(kept) && kept > 0 ? kept : null
  } catch {
    return null
  }
}

/** Remembers a drill's score if it is the best yet, and says whether it was. */
export function keepIfBest(right: number): boolean {
  if (right <= (bestDrill() ?? 0)) return false
  try {
    localStorage.setItem(KEY, String(right))
  } catch {
    // A browser that will keep nothing still shows this drill's score.
  }
  return true
}
