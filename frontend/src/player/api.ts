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

export async function renamePlayer(name: string): Promise<void> {
  await request('/players/me', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
}
