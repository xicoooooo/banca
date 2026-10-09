import { httpUrl } from '../casino/server'
import { Refused } from '../player/api'
import { playerToken } from '../player/identity'

/** A friend as the player is shown them. `joinAt` is where to go to sit down with them, when there is somewhere. */
export type Friend = { id: string; name: string; league: string; online: boolean; doing: string | null; joinAt: string | null }

/** Someone who has asked to be the player's friend, or whom the player has asked. */
export type FriendRequest = { id: string; name: string }

export type FriendsView = { code: string; friends: Friend[]; incoming: FriendRequest[]; outgoing: FriendRequest[] }

/** Thrown when the player is a guest. Friends are for players who have signed in. */
export class NeedsSignIn extends Error {}

async function friendsCall(path: string, init: RequestInit = {}): Promise<FriendsView> {
  const token = await playerToken()
  const response = await fetch(httpUrl(path), { ...init, headers: { ...init.headers, Authorization: `Bearer ${token}` } })
  if (response.status === 403) throw new NeedsSignIn()
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Refused(problem?.message ?? 'That could not be done just now. Try again in a moment.')
  }
  return response.json() as Promise<FriendsView>
}

export const fetchFriends = () => friendsCall('/friends')

/** Asks someone to be a friend, by the code they gave out or by their id from a page of theirs. */
export const askFriend = (who: { code: string } | { id: string }) =>
  friendsCall('/friends', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(who) })

export const acceptFriend = (id: string) => friendsCall(`/friends/${id}/accept`, { method: 'POST' })

/** Ends a friendship, turns a request down, or takes one back. */
export const removeFriend = (id: string) => friendsCall(`/friends/${id}`, { method: 'DELETE' })

/** A friend code as it is shown and read out: capitals, in two groups of four. */
export function shownFriendCode(code: string): string {
  const capitals = code.toUpperCase()
  return capitals.length === 8 ? `${capitals.slice(0, 4)} ${capitals.slice(4)}` : capitals
}

/** What a player typed, reduced to what a code can hold. */
export function typedFriendCode(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 8)
}
