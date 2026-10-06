import { httpUrl } from '../casino/server'
import { forgetPlayer, playerToken } from './identity'
import type { Dashboard } from './types'

/** A request the server understood and refused, with its reason. */
export class Refused extends Error {}

async function request(path: string, init: RequestInit = {}, secondTry = false): Promise<Response> {
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
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Refused(problem?.message ?? 'That was not accepted')
  }
  if (!response.ok) throw new Error(`The server answered ${response.status}`)
  return response
}

export async function fetchDashboard(): Promise<Dashboard> {
  return (await request('/players/me/dashboard')).json() as Promise<Dashboard>
}

export async function renamePlayer(name: string): Promise<void> {
  await request('/players/me', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
}
