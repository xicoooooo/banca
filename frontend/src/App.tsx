import { useEffect, useState } from 'react'
import { BlackjackTable } from './blackjack/BlackjackTable'
import { CasinoShell } from './casino/CasinoShell'
import { Loading } from './casino/Loading'
import { Lobby, type Game } from './lobby/Lobby'
import { confirmSignIn, returningFromSignIn } from './player/account'
import { Profile } from './profile/Profile'
import { Table } from './table/Table'

type Screen = Game | 'profile'

const SCREENS: Screen[] = ['poker', 'blackjack', 'profile']

function screenInAddress(): Screen | null {
  const name = window.location.hash.replace(/^#\/?/, '')
  return SCREENS.find((screen) => screen === name) ?? null
}

/**
 * Which screen is open is kept in the address, after the #, so the browser's
 * back button leaves a table and a link can point straight at one. That is all
 * the routing a handful of screens need.
 */
function App() {
  // Read once: the address is tidied straight after, and the answer must outlive that.
  const [returning] = useState(returningFromSignIn)
  const [screen, setScreen] = useState<Screen | null>(() => (returning ? 'profile' : screenInAddress()))
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
    const onChange = () => setScreen(screenInAddress())
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
  if (screen === 'profile') return <Profile onLeave={leave} onPlay={open} signInFailed={arrival === 'failed'} />
  return <Lobby onChoose={open} onProfile={() => open('profile')} />
}

export default App
