import { useEffect, useState } from 'react'
import { BlackjackTable } from './blackjack/BlackjackTable'
import { Lobby, type Game } from './lobby/Lobby'
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
  const [screen, setScreen] = useState<Screen | null>(screenInAddress)

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

  if (screen === 'poker') return <Table onLeave={leave} />
  if (screen === 'blackjack') return <BlackjackTable onLeave={leave} />
  if (screen === 'profile') return <Profile onLeave={leave} onPlay={open} />
  return <Lobby onChoose={open} onProfile={() => open('profile')} />
}

export default App
