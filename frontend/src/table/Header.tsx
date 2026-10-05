import { useState } from 'react'
import { isMuted, setMuted, sound } from '../casino/sound'
import type { TableView } from './types'

type Status = { text: string; tone: 'gold' | 'emerald' | 'quiet' }

function statusOf(view: TableView): Status {
  if (view.result) return { text: 'Hand over', tone: 'quiet' }
  if (view.actorSeat === view.yourSeat) return { text: 'Your turn', tone: 'gold' }
  if (view.actorSeat !== null) return { text: 'AI thinking', tone: 'emerald' }
  return { text: 'Dealing', tone: 'quiet' }
}

const DOT: Record<Status['tone'], string> = {
  gold: 'bg-gold-bright shadow-[0_0_10px_rgba(241,199,91,0.8)]',
  emerald: 'bg-emerald-300 shadow-[0_0_10px_rgba(110,231,183,0.7)] animate-pulse',
  quiet: 'bg-white/30',
}

/** A strip of smoked glass floating over the table, not a navigation bar. */
export function Header({ view }: { view: TableView }) {
  const [muted, setMutedState] = useState(isMuted)
  const status = statusOf(view)

  const toggleSound = () => {
    const next = !muted
    setMuted(next)
    setMutedState(next)
    if (!next) sound.click()
  }

  return (
    <header className="glass flex items-center gap-3 rounded-2xl px-3 py-2">
      <img src="/logo-192.png" alt="" width={32} height={32} className="h-8 w-8 rounded-lg" />

      <div className="min-w-0 flex-1 leading-tight">
        <p className="text-sm font-semibold tracking-[0.18em] text-ivory uppercase">Banca</p>
        <p className="label figure truncate">
          Hand {view.handNumber} · {view.smallBlind}/{view.bigBlind}
        </p>
      </div>

      <p className="flex items-center gap-2" aria-live="polite">
        <span aria-hidden className={`h-1.5 w-1.5 rounded-full ${DOT[status.tone]}`} />
        <span className={`label ${status.tone === 'gold' ? 'text-gold-bright!' : ''}`}>{status.text}</span>
      </p>

      <button
        type="button"
        onClick={toggleSound}
        aria-pressed={!muted}
        aria-label={muted ? 'Turn sound on' : 'Turn sound off'}
        className="grid h-9 w-9 place-items-center rounded-xl text-muted transition hover:bg-white/5 hover:text-ivory"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M4 9.5h3l4.5-3.5v12L7 14.5H4z" />
          {muted ? (
            <path d="M16 9.5l4.5 5M20.5 9.5l-4.5 5" />
          ) : (
            <path d="M15.5 9a4 4 0 010 6M18 6.5a7.5 7.5 0 010 11" />
          )}
        </svg>
      </button>
    </header>
  )
}
