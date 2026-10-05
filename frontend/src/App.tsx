import { useEffect, useState } from 'react'
import { BlackjackTable } from './blackjack/BlackjackTable'
import { Lobby, type Game } from './lobby/Lobby'
import { Table } from './table/Table'

const GAMES: Game[] = ['poker', 'blackjack']

function gameInAddress(): Game | null {
  const name = window.location.hash.replace(/^#\/?/, '')
  return GAMES.find((game) => game === name) ?? null
}

/**
 * Which table is open is kept in the address, after the #, so the browser's
 * back button leaves a table and a link can point straight at one. That is all
 * the routing three screens need.
 */
function App() {
  const [game, setGame] = useState<Game | null>(gameInAddress)

  useEffect(() => {
    const onChange = () => setGame(gameInAddress())
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  const leave = () => {
    window.location.hash = ''
  }

  if (game === 'poker') return <Table onLeave={leave} />
  if (game === 'blackjack') return <BlackjackTable onLeave={leave} />
  return (
    <Lobby
      onChoose={(chosen) => {
        window.location.hash = `/${chosen}`
      }}
    />
  )
}

export default App
