// The player dashboard as the server sends it. Every figure is worked out on
// the server from the rounds the player has really played; a null means there
// is not yet anything to work it out from.

export type GameId = 'poker' | 'blackjack'
export type Outcome = 'win' | 'loss' | 'push'

export type Dashboard = {
  player: {
    name: string
    /** True when the profile is saved to an account, false for a guest. */
    signedIn: boolean
    memberSince: string
    level: number
    title: string
    xp: number
    levelStart: number
    nextLevelAt: number
  }
  bankroll: {
    balance: number
    net: number
    granted: number
    peak: number
    history: { at: string; balance: number }[]
  }
  totals: {
    rounds: number
    wins: number
    losses: number
    pushes: number
    winRate: number | null
    biggestWin: number | null
    biggestLoss: number | null
    averageResult: number | null
    staked: number
    biggestPot: number | null
  }
  streaks: { currentKind: 'win' | 'loss' | null; current: number; bestWin: number; worstLoss: number }
  games: GameBreakdown[]
  activity: { date: string; rounds: number; net: number }[]
  achievements: Achievement[]
  recent: { game: GameId; at: string; net: number; outcome: Outcome; summary: string }[]
}

export type GameBreakdown = {
  game: GameId
  rounds: number
  wins: number
  losses: number
  pushes: number
  winRate: number | null
  net: number
  biggestWin: number | null
  tendencies: { label: string; value: string; basis: string }[]
}

export type Achievement = {
  id: string
  name: string
  description: string
  earned: boolean
  progress: number
  target: number
}
