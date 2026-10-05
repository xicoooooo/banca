import { useState } from 'react'
import { isMuted, setMuted, sound } from './sound'

export type Status = { text: string; tone: 'gold' | 'emerald' | 'quiet' }

const DOT: Record<Status['tone'], string> = {
  gold: 'bg-gold-bright shadow-[0_0_10px_rgba(241,199,91,0.8)]',
  emerald: 'bg-emerald-300 shadow-[0_0_10px_rgba(110,231,183,0.7)] animate-pulse',
  quiet: 'bg-white/30',
}

type HeaderProps = {
  /** A line under the name, such as the game and the round. */
  detail: string
  status?: Status
  /** Leaves the table for the lobby. Left out, the logo is not a button. */
  onLeave?: () => void
}

/** A strip of smoked glass floating over the table, the same at every game. */
export function Header({ detail, status, onLeave }: HeaderProps) {
  const [muted, setMutedState] = useState(isMuted)

  const toggleSound = () => {
    const next = !muted
    setMuted(next)
    setMutedState(next)
    if (!next) sound.click()
  }

  const identity = (
    <>
      <img src="/logo-192.png" alt="" width={32} height={32} className="h-8 w-8 rounded-lg" />
      <span className="min-w-0 flex-1 text-left leading-tight">
        <span className="block text-sm font-semibold tracking-[0.18em] text-ivory uppercase">Banca</span>
        <span className="label figure block truncate tracking-[0.1em]!">{detail}</span>
      </span>
    </>
  )

  return (
    <header className="glass flex items-center gap-2.5 rounded-2xl px-3 py-2">
      {onLeave ? (
        <button
          type="button"
          onClick={onLeave}
          aria-label="Leave the table for the lobby"
          className="-m-1 flex min-w-0 flex-1 items-center gap-2 rounded-xl p-1 transition hover:bg-white/5"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="flex-none text-muted">
            <path d="M15 5l-7 7 7 7" />
          </svg>
          {identity}
        </button>
      ) : (
        <div className="flex min-w-0 flex-1 items-center gap-3">{identity}</div>
      )}

      {status && (
        <p className="flex items-center gap-2" aria-live="polite">
          <span aria-hidden className={`h-1.5 w-1.5 rounded-full ${DOT[status.tone]}`} />
          {/* A new status rises into place instead of swapping. */}
          <span key={status.text} className={`label rise-in ${status.tone === 'gold' ? 'text-gold-bright!' : ''}`}>
            {status.text}
          </span>
        </p>
      )}

      <button
        type="button"
        onClick={toggleSound}
        aria-pressed={!muted}
        aria-label={muted ? 'Turn sound on' : 'Turn sound off'}
        className="-mr-1 grid h-9 w-8 flex-none place-items-center rounded-xl text-muted transition hover:bg-white/5 hover:text-ivory"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M4 9.5h3l4.5-3.5v12L7 14.5H4z" />
          {muted ? <path d="M16 9.5l4.5 5M20.5 9.5l-4.5 5" /> : <path d="M15.5 9a4 4 0 010 6M18 6.5a7.5 7.5 0 010 11" />}
        </svg>
      </button>
    </header>
  )
}
