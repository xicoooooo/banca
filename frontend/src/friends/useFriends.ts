import { useCallback, useEffect, useState } from 'react'
import { NeedsSignIn, fetchFriends, type FriendsView } from './api'

/** Friends as the server has them: not yet known, for a guest, unreachable, or here. */
export type FriendsState = { status: 'loading' } | { status: 'guest' } | { status: 'failed' } | { status: 'ready'; view: FriendsView }

/**
 * The player's friends, asked for again every so often so that who is online
 * stays true. Asking is also what tells the server this player is about, which
 * is how their own friends see them in the lobby.
 */
export function useFriends(everyMs: number) {
  const [state, setState] = useState<FriendsState>({ status: 'loading' })

  const take = useCallback((view: FriendsView) => setState({ status: 'ready', view }), [])

  useEffect(() => {
    let disposed = false
    const load = () =>
      fetchFriends().then(
        (view) => {
          if (!disposed) setState({ status: 'ready', view })
        },
        (problem) => {
          if (disposed) return
          // What is already shown stays shown through one failed look.
          setState((current): FriendsState => (problem instanceof NeedsSignIn ? { status: 'guest' } : current.status === 'ready' ? current : { status: 'failed' }))
        },
      )
    void load()
    // Not while the page is out of sight: nobody is looking, and the player is not really here.
    const timer = setInterval(() => !document.hidden && void load(), everyMs)
    return () => {
      disposed = true
      clearInterval(timer)
    }
  }, [everyMs])

  return { state, take }
}
