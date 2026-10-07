import { useEffect, useRef } from 'react'

/**
 * Opens the table's panel, once, for a player who has a private table to
 * themselves. That is nearly always the player who has just opened it, and the
 * panel is where the link to send is, so it saves them looking for it.
 */
export function useInviteOffer(aloneAtPrivateTable: boolean, show: () => void) {
  const offered = useRef(false)
  useEffect(() => {
    if (!aloneAtPrivateTable || offered.current) return
    offered.current = true
    show()
  }, [aloneAtPrivateTable, show])
}
