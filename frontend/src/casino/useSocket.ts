import { useCallback, useEffect, useRef, useState } from 'react'
import { forgetPlayer, playerToken } from '../player/identity'
import { socketUrl } from './server'

// Free hosting puts the backend to sleep when idle and takes up to a minute to
// wake it, so a connection that fails is retried for a while.
const RETRY_EVERY_MS = 3_000
const MAX_ATTEMPTS = 30
const RECONNECT_AFTER_MS = 600

/** The code the server closes a connection with when its table has been opened somewhere else. */
const REPLACED_CODE = 4001

/**
 * Where the connection to a table stands. "reconnecting" is a table that was
 * open and has dropped: the server keeps it for a few minutes, so it is worth
 * going back for. "replaced" is one the player has opened somewhere else.
 */
export type Connection = 'connecting' | 'open' | 'reconnecting' | 'replaced' | 'closed'

/** What the server says before the game begins: who it takes this player to be, or that it does not know them. */
type Greeting = { type: 'welcome' } | { type: 'error'; code?: string }

/**
 * A connection to one game's table, shared by every game. It says who the
 * player is before anything else, and from then on messages from the server
 * are handed to [onMessage] as they arrive; what they mean is the game's
 * business.
 *
 * The table lives on the server, not on the wire. If the connection drops it
 * is made again, and the server answers with the table as it was left.
 */
export function useSocket<Incoming, Outgoing>(path: string, onMessage: (message: Incoming) => void) {
  const [connection, setConnection] = useState<Connection>('connecting')
  /** How many times the player has been seated: once at first, and again after each drop. */
  const [sittings, setSittings] = useState(0)
  const socket = useRef<WebSocket | null>(null)

  // The handler is redefined on every render; the socket should not be.
  const handler = useRef(onMessage)
  useEffect(() => {
    handler.current = onMessage
  })

  useEffect(() => {
    let disposed = false
    let attempts = 0
    let retry: ReturnType<typeof setTimeout> | undefined

    const tryAgainOrGiveUp = (after = RETRY_EVERY_MS) => {
      if (attempts < MAX_ATTEMPTS) retry = setTimeout(connect, after)
      else setConnection('closed')
    }

    const connect = async () => {
      attempts++

      let token: string
      try {
        token = await playerToken()
      } catch {
        if (!disposed) tryAgainOrGiveUp()
        return
      }
      if (disposed) return

      let welcomed = false
      let stranger = false
      let replaced = false
      const ws = new WebSocket(socketUrl(path))
      socket.current = ws

      ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', token }))

      ws.onmessage = (event) => {
        const message = JSON.parse(event.data as string) as Incoming | Greeting
        const greeting = message as Greeting

        if (!welcomed) {
          if (greeting.type === 'welcome') {
            welcomed = true
            // Seated, so the next drop gets a full run of attempts of its own.
            attempts = 0
            setConnection('open')
            setSittings((count) => count + 1)
          } else if (greeting.type === 'error' && greeting.code === 'unknown_player') {
            // The server has forgotten this guest; the next attempt asks for a new one.
            stranger = true
            forgetPlayer(token)
          }
          return
        }

        // The table has been opened in another tab or on another device, which
        // now has it. Going back for it would only take it away again.
        if (greeting.type === 'error' && greeting.code === 'replaced') {
          replaced = true
          setConnection('replaced')
          return
        }
        handler.current(message as Incoming)
      }

      ws.onclose = (event) => {
        // The server closes a replaced connection with a code of its own, in
        // case the message saying so never arrived.
        if (event.code === REPLACED_CODE && !disposed) {
          replaced = true
          setConnection('replaced')
        }
        if (disposed || replaced) return
        if (stranger && attempts < MAX_ATTEMPTS) {
          void connect()
          return
        }
        if (welcomed) {
          // A table that was open is still there on the server: go straight back for it.
          setConnection('reconnecting')
          tryAgainOrGiveUp(RECONNECT_AFTER_MS)
        } else {
          tryAgainOrGiveUp()
        }
      }
    }

    void connect()

    return () => {
      disposed = true
      clearTimeout(retry)
      socket.current?.close()
    }
  }, [path])

  const send = useCallback((message: Outgoing) => {
    // What is said while the line is down is lost, not queued: by the time it
    // is back the table may have moved on, and the player can see that it has.
    if (socket.current?.readyState === WebSocket.OPEN) socket.current.send(JSON.stringify(message))
  }, [])

  return { connection, sittings, send }
}
