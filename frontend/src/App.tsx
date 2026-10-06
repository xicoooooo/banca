import { useEffect, useState } from 'react'
import { BlackjackTable } from './blackjack/BlackjackTable'
import { CasinoShell } from './casino/CasinoShell'
import { Loading } from './casino/Loading'
import { Lobby, type Game } from './lobby/Lobby'
import { confirmSignIn, returningFromSignIn } from './player/account'
import { Profile } from './profile/Profile'
import { RoomPicker } from './roulette/RoomPicker'
import { RoomTable } from './roulette/RoomTable'
import { RouletteTable } from './roulette/RouletteTable'
import { Table } from './table/Table'

type Screen = Game | 'profile'

const SCREENS: Screen[] = ['poker', 'blackjack', 'roulette', 'profile']

/** Where the address points: a screen, and for roulette which room within it. */
type Place = { screen: Screen; room: string | null }

function placeInAddress(): Place | null {
  const [name, room] = window.location.hash.replace(/^#\/?/, '').split('/')
  const screen = SCREENS.find((known) => known === name)
  // A room is named by a short word; anything else in the address is not one.
  return screen ? { screen, room: room && /^[a-z]{1,20}$/.test(room) ? room : null } : null
}

/**
 * Which screen is open is kept in the address, after the #, so the browser's
 * back button leaves a table and a link can point straight at one, down to the
 * room. That is all the routing a handful of screens need.
 */
function App() {
  // Read once: the address is tidied straight after, and the answer must outlive that.
  const [returning] = useState(returningFromSignIn)
  const [place, setPlace] = useState<Place | null>(() => (returning ? { screen: 'profile', room: null } : placeInAddress()))
  const screen = place?.screen ?? null
  // Set while a player who has just come back from signing in is being confirmed.
  const [arrival, setArrival] = useState<'none' | 'confirming' | 'failed'>(returning ? 'confirming' : 'none')

  useEffect(() => {
    if (!returning) return
    // The code works once, so it is taken out of the address before anything else.
    window.history.replaceState(null, '', window.location.pathname + '#/profile')

    let disposed = false
    void confirmSignIn(returning.code).then((worked) => {
      if (!disposed) setArrival(worked ? 'none' : 'failed')
    })
    return () => {
      disposed = true
    }
  }, [returning])

  useEffect(() => {
    const onChange = () => setPlace(placeInAddress())
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  const leave = () => {
    window.location.hash = ''
  }

  const open = (next: Screen) => {
    window.location.hash = `/${next}`
  }

  if (arrival === 'confirming') {
    return (
      <CasinoShell>
        <Loading message="Signing you in" />
      </CasinoShell>
    )
  }

  if (screen === 'poker') return <Table onLeave={leave} />
  if (screen === 'blackjack') return <BlackjackTable onLeave={leave} />
  if (screen === 'roulette') {
    // Leaving a room goes back to the choice of rooms, not all the way out.
    const toRooms = () => open('roulette')
    if (place?.room === 'private') return <RouletteTable onLeave={toRooms} />
    if (place?.room) return <RoomTable key={place.room} roomId={place.room} onLeave={toRooms} />
    return (
      <RoomPicker
        onLeave={leave}
        onChoose={(room) => {
          window.location.hash = `/roulette/${room}`
        }}
      />
    )
  }
  if (screen === 'profile') return <Profile onLeave={leave} onPlay={open} signInFailed={arrival === 'failed'} />
  return <Lobby onChoose={open} onProfile={() => open('profile')} />
}

export default App
