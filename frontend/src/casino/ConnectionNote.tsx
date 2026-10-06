import type { Connection } from './useSocket'

const NOTE: Partial<Record<Connection, string>> = {
  reconnecting: 'Connection lost. Getting you back to your table…',
  replaced: 'This table is now open somewhere else. Reload to bring it back here.',
  closed: 'Could not reach the table. Reload to try again.',
}

/** A line above the controls when the connection to the table is not what it should be. */
export function ConnectionNote({ connection }: { connection: Connection }) {
  const note = NOTE[connection]
  if (!note) return null
  return (
    <p role="status" className="label pb-2 text-center text-gold-bright!">
      {note}
    </p>
  )
}
