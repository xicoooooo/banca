import { httpUrl } from '../casino/server'
import { forgetPlayer, playerToken } from './identity'
import type { Dashboard, GameId, League, PublicProfile, TopList } from './types'

/** A request the server understood and refused, with its reason. */
export class Refused extends Error {}

export async function request(path: string, init: RequestInit = {}, secondTry = false): Promise<Response> {
  const token = await playerToken()
  const response = await fetch(httpUrl(path), {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  })

  // The server has forgotten this guest. Start again as a new one, once.
  if (response.status === 401 && !secondTry) {
    forgetPlayer(token)
    return request(path, init, true)
  }
  if (response.status === 400 || response.status === 409) {
    const problem = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Refused(problem?.message ?? 'That was not accepted')
  }
  if (!response.ok) throw new Error(`The server answered ${response.status}`)
  return response
}

export async function fetchDashboard(): Promise<Dashboard> {
  return (await request('/players/me/dashboard')).json() as Promise<Dashboard>
}

/** What anyone may see of the signed-in player with this id. Fails if there is no such player. */
export async function fetchPublicProfile(id: string): Promise<PublicProfile> {
  return (await request(`/profiles/${encodeURIComponent(id)}`)).json() as Promise<PublicProfile>
}

export async function fetchLeague(): Promise<League> {
  return (await request('/league')).json() as Promise<League>
}

/** The biggest winners among signed-in players: this week or of all time, at one game or all of them. */
export async function fetchTop(period: 'week' | 'all', game: GameId | null): Promise<TopList> {
  const query = new URLSearchParams({ period, ...(game ? { game } : {}) })
  return (await request(`/leaderboard?${query}`)).json() as Promise<TopList>
}

/** Claims today's reward and returns what it was worth. */
export async function claimDailyReward(): Promise<number> {
  const response = await request('/players/me/rewards/daily', { method: 'POST' })
  return ((await response.json()) as { granted: number }).granted
}

/** Collects the chips for a mission that has been done, or for all three, and returns what it was worth. */
export async function claimMission(slot: number): Promise<number> {
  const response = await request(`/players/me/missions/${slot}`, { method: 'POST' })
  return ((await response.json()) as { granted: number }).granted
}

/** Wears a title the player has earned, or none when it is null. Refused for a title that is not theirs. */
export async function wearTitle(title: string | null): Promise<void> {
  await request('/players/me/title', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
}

/** Sets a challenge in the player's name and returns its code. */
export async function setChallenge(): Promise<string> {
  const response = await request('/blackjack/challenges', { method: 'POST' })
  return ((await response.json()) as { code: string }).code
}

/** The challenges the player has set or taken, newest first. */
export async function fetchMyChallenges(): Promise<import('../blackjack/types').ChallengeView[]> {
  return (await request('/players/me/challenges')).json() as Promise<import('../blackjack/types').ChallengeView[]>
}

export async function renamePlayer(name: string): Promise<void> {
  await request('/players/me', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
}

/** What a host chooses for a private table. Anything left out is the game's usual. */
export type TableOptions = { seats?: number; banca?: boolean; turns?: 'normal' | 'long'; chips?: 'real' | 'practice'; format?: 'cash' | 'tournament' }

/**
 * Opens a table for the player to invite others to, at the address the game
 * lists its shared tables at, and returns where it is.
 */
export async function openTable(listedAt: string, options: TableOptions = {}): Promise<{ id: string; name: string }> {
  const token = await playerToken()
  const response = await fetch(httpUrl(listedAt), {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(options),
  })
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Refused(problem?.message ?? 'The table could not be opened. Try again in a moment.')
  }
  return response.json() as Promise<{ id: string; name: string }>
}

/** A private table found by its code: which game it is and where. */
/** What a code leads to: a private table at one of the games, or a blackjack challenge. */
export type FoundTable = { game: GameId | 'challenge'; id: string; name: string }

/** Looks a private table up by the code its host gave out. Refused, with the reason, when there is no such table. */
export async function findTable(code: string): Promise<FoundTable> {
  const response = await fetch(httpUrl(`/tables/${encodeURIComponent(code.trim())}`))
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Refused(problem?.message ?? 'That code could not be checked. Try again in a moment.')
  }
  return response.json() as Promise<FoundTable>
}
