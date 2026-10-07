// The player dashboard as the server sends it. Every figure is worked out on
// the server from the rounds the player has really played; a null means there
// is not yet anything to work it out from.

export type GameId = 'poker' | 'blackjack' | 'roulette'
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
  rewards: Rewards
}

/** The chips a player can come by without winning them, and when. */
export type Rewards = {
  daily: {
    available: boolean
    /** What the next claim is worth, and which day of the week of rewards it is, from 1. */
    amount: number
    day: number
    streak: number
    /** When the next claim opens, or null when one is open now. */
    nextAt: string | null
    ladder: number[]
  }
  /** What the house stakes a player who is out of chips, and when it next will. Null means now. */
  rescue: { amount: number; nextAt: string | null }
}

/** Sent by a table that will not deal because the player cannot cover the smallest bet. */
export type Broke = { dailyReady: boolean; nextChipsAt: string }

/** What any table may say about the player's chips, besides its own messages. */
export type ChipNotice = { type: 'staked'; amount: number } | ({ type: 'broke' } & Broke)

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

/** Where a player would end up if the week finished now. */
export type Zone = 'promotion' | 'safe' | 'demotion'

/** One player's line in their league this week. `position` is 0 for someone who has not played yet. */
export type LeagueRow = { position: number; name: string; net: number; rounds: number; you: boolean; zone: Zone }

/** The league the player is in this week, or the lowest one for a guest to look at. */
export type League = {
  /** False for a guest, who can look at a league but is not in one. */
  signedIn: boolean
  tier: number
  tierName: string
  tiers: string[]
  weekStart: string
  endsAt: string
  /** How many players are in this league, whether or not they have played this week. */
  players: number
  rows: LeagueRow[]
  lastWeek: { tier: string; position: number; net: number; outcome: 'promoted' | 'stayed' | 'demoted'; prize: number } | null
  rules: { promoted: number; demoted: number; demotionNeeds: number; minRounds: number; prizes: number[] }
}

export type TopRow = { position: number; name: string; league: string; net: number; rounds: number; you: boolean }

export type TopList = { period: 'week' | 'all'; game: GameId | null; rows: TopRow[] }
