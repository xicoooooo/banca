import type { ReactNode } from 'react'
import { CloseTable, InviteButton, TableCode } from './Invite'

type WaitingRoomProps = {
  table: string
  /** Everyone here, in the order they sit. */
  seats: { name: string; you: boolean; host: boolean; house?: boolean }[]
  seatsInAll: number
  /** Who starts the game, and whether that is this player. */
  host: string | null
  youHost: boolean
  /** Why the game cannot begin yet, for the host. Null when it can. */
  notYet: string | null
  onStart: () => void
  /** The table's code, for telling someone who cannot be sent a link. */
  code: string
  practice: boolean
  /** Closes the table for everyone. Given only to the host. */
  onEnd?: () => void
  /** The way into the table's chat. */
  children?: ReactNode
}

/**
 * A private table before its game begins: who has arrived, the seats still
 * empty, the link to fill them, and for the host the button that starts it.
 */
export function WaitingRoom({ table, seats, seatsInAll, host, youHost, notYet, onStart, code, practice, onEnd, children }: WaitingRoomProps) {
  const empty = Math.max(0, seatsInAll - seats.length)

  return (
    <div className="m-auto flex w-full max-w-md flex-col gap-4 py-6">
      <div className="text-center">
        <p className="label text-gold!">A private table</p>
        <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">{table}</h1>
        <p className="pt-2 text-sm leading-relaxed text-muted">
          {youHost ? 'Send the link to your friends. The game begins when you say.' : `Waiting for ${host ?? 'the host'} to start the game.`}
        </p>
      </div>

      <div className="flex justify-center">
        <TableCode id={code} />
      </div>

      <ul className="waiting-seats" aria-label={`${seats.length} of ${seatsInAll} seats taken`}>
        {seats.map((seat, index) => (
          <li key={`${seat.name}-${index}`} className="waiting-seat rise-in" data-you={seat.you} style={{ ['--rise-delay' as string]: `${index * 60}ms` }}>
            <span aria-hidden className="monogram monogram--small">
              {seat.name.trim().charAt(0).toUpperCase()}
            </span>
            <span className="min-w-0 flex-1 truncate text-base font-semibold tracking-tight">{seat.you ? 'You' : seat.name}</span>
            <span className="label tracking-[0.12em]!">{seat.house ? 'The house' : seat.host ? 'Host' : 'Here'}</span>
          </li>
        ))}
        {Array.from({ length: empty }, (_, index) => (
          <li key={`empty-${index}`} className="waiting-seat waiting-seat--empty">
            <span aria-hidden className="monogram monogram--small monogram--empty" />
            <span className="flex-1 text-sm text-muted">Empty seat</span>
          </li>
        ))}
      </ul>

      <div className="flex flex-col gap-2.5">
        {youHost && (
          <button type="button" className="btn btn--raise" onClick={onStart} disabled={notYet !== null}>
            Start the game
          </button>
        )}
        <div className="flex items-center justify-center gap-2">
          <InviteButton table={table} prominent={!youHost || notYet !== null} />
          {children}
        </div>
        {youHost && notYet && <p className="label text-center leading-relaxed">{notYet}</p>}
      </div>

      <p className="label pt-1 text-center leading-relaxed">
        {practice ? 'Practice chips · nothing here touches anyone\u2019s own' : 'Played with your own chips · does not count for the leagues'}
        <br />
        Only people with the link or the code can find this table
      </p>
      {onEnd && (
        <div className="flex justify-center">
          <CloseTable onClose={onEnd} />
        </div>
      )}
    </div>
  )
}
