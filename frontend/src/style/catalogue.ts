// The card backs and table felts a player can collect, and what each one asks
// for. Nothing here is bought: everything is opened by playing, and worked out
// from the same record of rounds, leagues and trophies as the rest of the profile.

export type Kind = 'cards' | 'felt'

/** What opens an item. Leagues count from the first, so "Gold" is asked for by name. */
export type Unlock =
  | { by: 'start' }
  | { by: 'level'; level: number }
  | { by: 'achievements'; count: number }
  | { by: 'league'; league: string }
  | { by: 'trophy' }

export type StyleItem = { id: string; kind: Kind; name: string; unlock: Unlock }

/** As much of a player's record as decides what they have opened. */
export type Progress = {
  level: number
  /** The league they are in now, or null for a guest, who is in none. */
  league: string | null
  /** The league each of their trophies was won in. */
  trophyLeagues: string[]
  achievements: number
}

/** The leagues, lowest first. */
export const LEAGUES = ['Bronze', 'Silver', 'Gold', 'Platinum', 'Emerald']

export const DEFAULTS: Record<Kind, string> = { cards: 'classic', felt: 'emerald' }

export const CATALOGUE: StyleItem[] = [
  { id: 'classic', kind: 'cards', name: 'Classic', unlock: { by: 'start' } },
  { id: 'midnight', kind: 'cards', name: 'Midnight', unlock: { by: 'level', level: 3 } },
  { id: 'burgundy', kind: 'cards', name: 'Burgundy', unlock: { by: 'level', level: 8 } },
  { id: 'ivory', kind: 'cards', name: 'Ivory', unlock: { by: 'achievements', count: 7 } },
  { id: 'gilded', kind: 'cards', name: 'Gilded', unlock: { by: 'league', league: 'Gold' } },
  { id: 'champion', kind: 'cards', name: 'Champion', unlock: { by: 'trophy' } },

  { id: 'emerald', kind: 'felt', name: 'Emerald', unlock: { by: 'start' } },
  { id: 'midnight', kind: 'felt', name: 'Midnight', unlock: { by: 'level', level: 5 } },
  { id: 'burgundy', kind: 'felt', name: 'Burgundy', unlock: { by: 'level', level: 12 } },
  { id: 'slate', kind: 'felt', name: 'Slate', unlock: { by: 'league', league: 'Silver' } },
  { id: 'royal', kind: 'felt', name: 'Royal', unlock: { by: 'league', league: 'Platinum' } },
]

export const itemsOf = (kind: Kind) => CATALOGUE.filter((item) => item.kind === kind)

/**
 * The highest league a player has reached. A league can be lost again at the
 * end of a bad week, and something earned should not be: so a trophy counts
 * for the league above the one it was won in, since a top-three finish is
 * what sends a player up.
 */
export function highestLeague(progress: Progress): number {
  const now = progress.league ? LEAGUES.indexOf(progress.league) : -1
  const won = progress.trophyLeagues.map((league) => Math.min(LEAGUES.indexOf(league) + 1, LEAGUES.length - 1))
  return Math.max(now, ...won)
}

export function isUnlocked(item: StyleItem, progress: Progress): boolean {
  const unlock = item.unlock
  switch (unlock.by) {
    case 'start':
      return true
    case 'level':
      return progress.level >= unlock.level
    case 'achievements':
      return progress.achievements >= unlock.count
    case 'league':
      return highestLeague(progress) >= LEAGUES.indexOf(unlock.league)
    case 'trophy':
      return progress.trophyLeagues.length > 0
  }
}

/** What an item asks for, in a few words, for the one who has not opened it yet. */
export function asksFor(unlock: Unlock): string {
  switch (unlock.by) {
    case 'start':
      return 'Yours from the start'
    case 'level':
      return `Level ${unlock.level}`
    case 'achievements':
      return `${unlock.count} achievements`
    case 'league':
      return `${unlock.league} League`
    case 'trophy':
      return 'Win a trophy'
  }
}

/** The choice to use: the one asked for if there is such a thing and it is open, and the plain one if not. */
export function allowed(kind: Kind, chosen: string, progress: Progress): string {
  const item = CATALOGUE.find((candidate) => candidate.kind === kind && candidate.id === chosen)
  return item && isUnlocked(item, progress) ? item.id : DEFAULTS[kind]
}
