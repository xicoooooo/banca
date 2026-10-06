import { useEffect, useState } from 'react'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { httpUrl } from '../casino/server'
import { colorOf } from './layout'
import type { RoomSummary } from './types'

// Often enough that a room filling up is seen, seldom enough to cost nothing.
const REFRESH_EVERY_MS = 5_000

type RoomPickerProps = {
  onLeave: () => void
  /** Opens a shared room by its id, or the private table. */
  onChoose: (room: string) => void
}

/** Where to play roulette: one of the shared rooms, or a table to oneself. */
export function RoomPicker({ onLeave, onChoose }: RoomPickerProps) {
  const [rooms, setRooms] = useState<RoomSummary[] | null>(null)

  useEffect(() => {
    let disposed = false
    const load = () =>
      fetch(httpUrl('/roulette/rooms'))
        .then((response) => response.json() as Promise<RoomSummary[]>)
        .then((list) => {
          if (!disposed) setRooms(list)
        })
        // The private table is still on offer if the rooms cannot be listed.
        .catch(() => undefined)
    void load()
    const timer = setInterval(load, REFRESH_EVERY_MS)
    return () => {
      disposed = true
      clearInterval(timer)
    }
  }, [])

  return (
    <CasinoShell>
      <Header detail="Roulette" onLeave={onLeave} />

      <div className="m-auto flex w-full max-w-md flex-col gap-3 py-6">
        <div className="pb-2 text-center">
          <p className="label text-gold!">One wheel for the whole room</p>
          <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">Choose a room</h1>
        </div>

        {(rooms ?? []).map((room, index) => (
          <button
            key={room.id}
            type="button"
            onClick={() => onChoose(room.id)}
            className="game-tile rise-in"
            style={{ ['--rise-delay' as string]: `${index * 70}ms` }}
          >
            <span className="flex items-baseline justify-between gap-3">
              <span className="text-xl font-semibold tracking-tight">{room.name}</span>
              <span className={`label ${room.players > 0 ? 'text-gold-bright!' : ''}`}>
                {room.players === 0 ? 'Empty' : `${room.players} playing`}
              </span>
            </span>
            <span className="history justify-start! pt-1" aria-label="Recent results, newest first">
              {room.history.length === 0 ? (
                <span className="label">The wheel is waiting for someone</span>
              ) : (
                room.history.map((pocket, at) => (
                  <span key={at} data-color={colorOf(pocket)} className="history__pocket figure">
                    {pocket}
                  </span>
                ))
              )}
            </span>
          </button>
        ))}

        <button type="button" onClick={() => onChoose('private')} className="game-tile rise-in" style={{ ['--rise-delay' as string]: '240ms' }}>
          <span className="label text-gold!">A table to yourself</span>
          <span className="text-xl font-semibold tracking-tight">Private table</span>
          <span className="text-sm leading-relaxed text-muted">No clock and nobody else. Spin when you are ready.</span>
        </button>

        <p className="label pt-2 text-center leading-relaxed">
          In a room, bets close every half minute
          <br />
          and the wheel turns once for everyone
        </p>
      </div>
    </CasinoShell>
  )
}
