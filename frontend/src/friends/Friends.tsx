import { useState, type FormEvent } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { Refused } from '../player/api'
import { acceptFriend, askFriend, removeFriend, shownFriendCode, typedFriendCode, type Friend, type FriendsView } from './api'
import { useFriends } from './useFriends'

type FriendsProps = {
  onLeave: () => void
  onSignIn: () => void
  /** Opens a player's public page. */
  onPlayer: (id: string) => void
}

/** How often the list is asked for again while it is open, so that who is online stays true. */
const REFRESH_EVERY_MS = 15_000

/**
 * A player's friends: who is here now and where, requests waiting for an
 * answer, their own code to give out, and somewhere to type someone else's.
 */
export function Friends({ onLeave, onSignIn, onPlayer }: FriendsProps) {
  const { state, take } = useFriends(REFRESH_EVERY_MS)

  return (
    <CasinoShell>
      <Header detail="Friends" onLeave={onLeave} />

      {state.status === 'loading' ? (
        <Loading message="Finding your friends" />
      ) : state.status === 'failed' ? (
        <Loading failed message="Could not reach the house. Try again in a minute." />
      ) : state.status === 'guest' ? (
        <div className="m-auto flex w-full max-w-md flex-col items-center gap-4 py-8 text-center">
          <p className="label text-gold!">Friends</p>
          <h1 className="text-3xl font-semibold tracking-tight text-ivory">Sign in to have friends</h1>
          <p className="text-sm leading-relaxed text-muted">
            A guest lives in one browser and cannot be found from another. Sign in, and you get a code to give your friends, and see when they are at
            a table.
          </p>
          <button type="button" className="btn btn--raise px-6!" onClick={onSignIn}>
            Go to your profile to sign in
          </button>
        </div>
      ) : (
        <FriendsList view={state.view} take={take} onPlayer={onPlayer} />
      )}
    </CasinoShell>
  )
}

function FriendsList({ view, take, onPlayer }: { view: FriendsView; take: (view: FriendsView) => void; onPlayer: (id: string) => void }) {
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [said, setSaid] = useState<{ text: string; good: boolean } | null>(null)
  const [copied, setCopied] = useState(false)
  const [removing, setRemoving] = useState<string | null>(null)
  const online = view.friends.filter((friend) => friend.online).length

  // Every change is answered with the list as it then stands, so nothing here is guessed at.
  const act = async (work: () => Promise<FriendsView>, done?: string) => {
    if (busy) return
    setBusy(true)
    setSaid(null)
    try {
      take(await work())
      if (done) setSaid({ text: done, good: true })
    } catch (problem) {
      setSaid({ text: problem instanceof Refused ? problem.message : 'That could not be done just now. Try again in a moment.', good: false })
    }
    setBusy(false)
    setRemoving(null)
  }

  const add = (event: FormEvent) => {
    event.preventDefault()
    if (code.length !== 8) return
    void act(() => askFriend({ code }), 'Request sent').then(() => setCode(''))
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(shownFriendCode(view.code))
      setCopied(true)
      setTimeout(() => setCopied(false), 2200)
    } catch {
      // It is there to be read, whether or not it can be copied.
    }
  }

  return (
    <div className="mx-auto flex w-full max-w-md flex-col gap-4 py-4 sm:py-6">
      <div className="pb-1 text-center">
        <p className="label text-gold!">{view.friends.length === 0 ? 'Nobody yet' : `${online} of ${view.friends.length} here now`}</p>
        <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">Friends</h1>
      </div>

      {view.incoming.length > 0 && (
        <section className="panel rise-in" aria-label="Requests to be your friend">
          <h2 className="label pb-3 text-gold!">Wants to be your friend</h2>
          <ul className="friends">
            {view.incoming.map((request) => (
              <li key={request.id} className="friend">
                <span aria-hidden className="monogram monogram--small">
                  {request.name.trim().charAt(0).toUpperCase()}
                </span>
                <button type="button" className="friend__name truncate" onClick={() => onPlayer(request.id)}>
                  {request.name}
                </button>
                <button type="button" className="btn btn--call px-3.5! py-2! text-sm" disabled={busy} onClick={() => act(() => acceptFriend(request.id))}>
                  Accept
                </button>
                <button type="button" className="btn btn--quiet px-3.5! py-2! text-sm" disabled={busy} onClick={() => act(() => removeFriend(request.id))}>
                  Decline
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="panel rise-in" style={{ ['--rise-delay' as string]: '50ms' }} aria-label="Your friends">
        {view.friends.length === 0 ? (
          <p className="py-3 text-center text-sm leading-relaxed text-muted">
            Give a friend your code below, or type theirs. Once they accept, you will see here when they are at a table.
          </p>
        ) : (
          <ul className="friends">
            {view.friends.map((friend) => (
              <FriendRow
                key={friend.id}
                friend={friend}
                busy={busy}
                asking={removing === friend.id}
                onPlayer={onPlayer}
                onAskRemove={() => setRemoving(friend.id)}
                onKeep={() => setRemoving(null)}
                onRemove={() => act(() => removeFriend(friend.id))}
              />
            ))}
          </ul>
        )}
      </section>

      <section className="panel rise-in" style={{ ['--rise-delay' as string]: '100ms' }}>
        <h2 className="label pb-3 text-gold!">Add a friend</h2>
        <div className="flex flex-wrap items-center justify-between gap-3 pb-4">
          <p className="min-w-0 flex-1 text-sm leading-relaxed text-ivory/80">Your code. Give it to a friend, or type theirs below.</p>
          <button type="button" className="table-code" onClick={copy} aria-label={`Your friend code, ${shownFriendCode(view.code).split('').join(' ')}. Press to copy.`}>
            <span className="label">{copied ? 'Copied' : 'Your code'}</span>
            <span className="table-code__digits figure">{shownFriendCode(view.code)}</span>
          </button>
        </div>

        <form onSubmit={add} className="flex gap-2">
          <label htmlFor="friend-code" className="sr-only">
            A friend's code
          </label>
          <input
            id="friend-code"
            className="join-code__field figure"
            value={shownFriendCode(code)}
            onChange={(event) => {
              setCode(typedFriendCode(event.target.value))
              setSaid(null)
            }}
            placeholder="ABCD EFGH"
            autoCapitalize="characters"
            autoCorrect="off"
            autoComplete="off"
            spellCheck={false}
          />
          <button type="submit" className="btn btn--call px-5!" disabled={code.length !== 8 || busy}>
            Add
          </button>
        </form>
        {said && (
          <p role={said.good ? 'status' : 'alert'} className={`pt-2.5 text-sm ${said.good ? 'text-ivory/85' : 'text-gold-bright'}`}>
            {said.text}
          </p>
        )}
      </section>

      {view.outgoing.length > 0 && (
        <section className="panel rise-in" style={{ ['--rise-delay' as string]: '150ms' }} aria-label="Requests you have sent">
          <h2 className="label pb-3 text-gold!">Waiting for an answer</h2>
          <ul className="friends">
            {view.outgoing.map((request) => (
              <li key={request.id} className="friend">
                <span aria-hidden className="monogram monogram--small">
                  {request.name.trim().charAt(0).toUpperCase()}
                </span>
                <button type="button" className="friend__name truncate" onClick={() => onPlayer(request.id)}>
                  {request.name}
                </button>
                <button type="button" className="btn btn--quiet px-3.5! py-2! text-sm" disabled={busy} onClick={() => act(() => removeFriend(request.id))}>
                  Take back
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <p className="label py-1 text-center leading-relaxed">
        A friend sees your name, your league, and when you are at a table
        <br />
        Never your chips or how you play
      </p>
    </div>
  )
}

type FriendRowProps = {
  friend: Friend
  busy: boolean
  /** True while the player is being asked whether they mean to remove this friend. */
  asking: boolean
  onPlayer: (id: string) => void
  onAskRemove: () => void
  onKeep: () => void
  onRemove: () => void
}

function FriendRow({ friend, busy, asking, onPlayer, onAskRemove, onKeep, onRemove }: FriendRowProps) {
  return (
    <li className="friend" data-online={friend.online}>
      <span aria-hidden className="monogram monogram--small friend__face">
        {friend.name.trim().charAt(0).toUpperCase()}
        <span className="friend__dot" />
      </span>
      <span className="min-w-0 flex-1">
        <button type="button" className="friend__name truncate" onClick={() => onPlayer(friend.id)}>
          {friend.name}
        </button>
        <span className="label block truncate tracking-[0.08em]!">
          <span className="sr-only">{friend.online ? 'Online. ' : 'Offline. '}</span>
          {friend.doing ?? (friend.online ? 'In the lobby' : `Offline · ${friend.league} League`)}
        </span>
      </span>

      {asking ? (
        <>
          <button type="button" className="btn btn--fold px-3.5! py-2! text-sm" disabled={busy} onClick={onRemove}>
            Remove
          </button>
          <button type="button" className="btn btn--quiet px-3.5! py-2! text-sm" onClick={onKeep}>
            Keep
          </button>
        </>
      ) : (
        <>
          {friend.joinAt && (
            <a href={`#/${friend.joinAt}`} className="btn btn--call px-3.5! py-2! text-sm">
              Join
            </a>
          )}
          <button type="button" className="friend__remove" onClick={onAskRemove} aria-label={`Remove ${friend.name} from your friends`}>
            ×
          </button>
        </>
      )}
    </li>
  )
}
