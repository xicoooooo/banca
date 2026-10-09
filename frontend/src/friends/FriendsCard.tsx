import { useFriends } from './useFriends'

/** How often the lobby asks after friends. Seldom enough to cost nothing, often enough to feel alive. */
const REFRESH_EVERY_MS = 30_000

/**
 * The way to a player's friends, in the lobby: how many are here now, and
 * whether anyone is waiting for an answer. Looking is also what lets the
 * server tell this player's own friends that they are about.
 */
export function FriendsCard({ onOpen }: { onOpen: () => void }) {
  const { state } = useFriends(REFRESH_EVERY_MS)
  if (state.status === 'loading' || state.status === 'failed') return null

  const view = state.status === 'ready' ? state.view : null
  const online = view?.friends.filter((friend) => friend.online).length ?? 0
  const waiting = view?.incoming.length ?? 0

  return (
    <button type="button" onClick={onOpen} className="league-card rise-in" style={{ ['--rise-delay' as string]: '50ms' }} aria-label="Open your friends">
      <span aria-hidden className="friends-mark" data-online={online > 0}>
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="9" cy="8" r="3.2" />
          <path d="M3 19c.6-3.2 3-5 6-5s5.4 1.8 6 5" />
          <circle cx="17" cy="9" r="2.4" />
          <path d="M16.5 14.2c2.4.2 4 1.7 4.5 4.3" />
        </svg>
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-base font-semibold tracking-tight">Friends</span>
        <span className="label block tracking-[0.12em]!">
          {!view
            ? 'Sign in to add friends'
            : view.friends.length === 0
              ? 'Add someone with their code'
              : online > 0
                ? `${online} here now`
                : `${view.friends.length} ${view.friends.length === 1 ? 'friend' : 'friends'} · none here now`}
        </span>
      </span>
      {waiting > 0 && <span className="friends-badge figure">{waiting}</span>}
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="flex-none text-muted">
        <path d="M9 5l7 7-7 7" />
      </svg>
    </button>
  )
}
