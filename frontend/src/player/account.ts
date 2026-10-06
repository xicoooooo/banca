import { httpUrl } from '../casino/server'
import { request } from './api'
import { forgetPlayer, playerToken, replaceToken } from './identity'

// Signing in saves the profile this browser has to an account, so it can be
// reached from another device. The provider is only used to find out who the
// player is: once the server has confirmed that, it is the server's own token
// that stands for the player, exactly as it does for a guest.

type Settings = { url: string | null; publicKey: string | null }

let settings: Promise<Settings> | null = null

function signInSettings(): Promise<Settings> {
  settings ??= fetch(httpUrl('/sign-in'))
    .then((response) => response.json() as Promise<Settings>)
    .catch(() => {
      settings = null
      return { url: null, publicKey: null }
    })
  return settings
}

/** Whether this server offers signing in at all. */
export async function canSignIn(): Promise<boolean> {
  return (await signInSettings()).url !== null
}

/** The provider's client, fetched only when someone signs in so that nobody else pays for it. */
async function provider() {
  const { url, publicKey } = await signInSettings()
  if (!url || !publicKey) throw new Error('Signing in is not set up')
  const { GoTrueClient } = await import('@supabase/auth-js')
  return new GoTrueClient({
    url: `${url}/auth/v1`,
    headers: { apikey: publicKey, Authorization: `Bearer ${publicKey}` },
    storageKey: 'banca.sign-in',
    flowType: 'pkce',
    // The way back is handled here, by hand, and no session is kept afterwards.
    detectSessionInUrl: false,
    autoRefreshToken: false,
  })
}

/** Sends the player to Google. They come back to this page with a code in the address. */
export async function signInWithGoogle(): Promise<void> {
  const client = await provider()
  const { error } = await client.signInWithOAuth({
    provider: 'google',
    options: { redirectTo: window.location.origin + '/' },
  })
  if (error) throw error
}

/** What the address says when the player has just come back from signing in. */
export function returningFromSignIn(): { code: string | null; failed: boolean } | null {
  const query = new URLSearchParams(window.location.search)
  if (query.has('code')) return { code: query.get('code'), failed: false }
  if (query.has('error')) return { code: null, failed: true }
  return null
}

let confirming: Promise<boolean> | null = null

/**
 * Finishes the sign-in the player has just come back from, once however many
 * times it is asked, since the code is spent by the first use.
 */
export function confirmSignIn(code: string | null): Promise<boolean> {
  confirming ??= code ? completeSignIn(code) : Promise.resolve(false)
  return confirming
}

/**
 * Trades the code for proof of who the player is, shows it to the server, and
 * takes up whichever profile the server says is theirs. Returns whether it
 * worked.
 */
async function completeSignIn(code: string): Promise<boolean> {
  try {
    const client = await provider()
    const { data, error } = await client.exchangeCodeForSession(code)
    if (error || !data.session) return false

    const response = await request('/players/me/account', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accessToken: data.session.access_token }),
    })
    const { token } = (await response.json()) as { token: string | null }
    if (token) replaceToken(token)

    // The provider's session has done its job and is not kept on this device.
    await client.signOut({ scope: 'local' }).catch(() => undefined)
    return true
  } catch {
    return false
  }
}

/** Signs this device out. The profile stays with its account; the device starts again as a guest. */
export async function signOut(): Promise<void> {
  const token = await playerToken()
  await request('/players/me/session', { method: 'DELETE' })
  forgetPlayer(token)
}
