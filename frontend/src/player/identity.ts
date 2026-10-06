import { httpUrl } from '../casino/server'

// Who this browser is. The server makes a guest the first time and hands back
// a token that stands for them from then on; nothing else is kept here, and
// the chips themselves live on the server.
const KEY = 'banca.player'

// Storage can be unavailable, in a private window for one. The visit still
// works, as a guest who lasts until the page is closed.
let remembered: string | null = null

function stored(): string | null {
  try {
    return window.localStorage.getItem(KEY) ?? remembered
  } catch {
    return remembered
  }
}

function store(token: string | null) {
  remembered = token
  try {
    if (token) window.localStorage.setItem(KEY, token)
    else window.localStorage.removeItem(KEY)
  } catch {
    // Kept in memory instead.
  }
}

let arriving: Promise<string> | null = null

async function createGuest(): Promise<string> {
  const response = await fetch(httpUrl('/players'), { method: 'POST' })
  if (!response.ok) throw new Error(`The server answered ${response.status}`)
  const { token } = (await response.json()) as { token: string }
  store(token)
  return token
}

/**
 * This browser's token, asking the server for a guest profile if there is not
 * one yet. Everything that needs it at once shares the one request, so a
 * first visit makes a single player and not several.
 */
export function playerToken(): Promise<string> {
  const token = stored()
  if (token) return Promise.resolve(token)
  arriving ??= createGuest().finally(() => {
    arriving = null
  })
  return arriving
}

/** Takes up the token for a saved profile, in place of the one this browser had. */
export function replaceToken(token: string) {
  store(token)
}

/** Drops a token the server no longer knows, so the next request starts afresh. */
export function forgetPlayer(token: string) {
  if (stored() === token) store(null)
}
