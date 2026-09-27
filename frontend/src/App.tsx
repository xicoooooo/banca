import { useEffect, useState } from 'react'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

type Health = { status: string; service: string; version: string }

type BackendState =
  | { kind: 'loading' }
  | { kind: 'up'; health: Health }
  | { kind: 'down' }

function App() {
  const [backend, setBackend] = useState<BackendState>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false

    fetch(`${API_BASE}/health`)
      .then((response) => response.json() as Promise<Health>)
      .then((health) => {
        if (!cancelled) setBackend({ kind: 'up', health })
      })
      .catch(() => {
        if (!cancelled) setBackend({ kind: 'down' })
      })

    return () => {
      cancelled = true
    }
  }, [])

  return (
    <main className="min-h-screen bg-felt-900 text-white flex flex-col items-center justify-center gap-6 px-4 text-center">
      <div>
        <h1 className="text-5xl font-semibold tracking-tight">Banca</h1>
        <p className="mt-2 text-white/70">Poker, blackjack and roulette. Play money only.</p>
      </div>

      <p className="text-sm text-white/50">
        {backend.kind === 'loading' && 'Checking the table…'}
        {backend.kind === 'up' && `Backend ${backend.health.version} is up.`}
        {backend.kind === 'down' && 'Backend unreachable. Start it with ./gradlew run.'}
      </p>
    </main>
  )
}

export default App
