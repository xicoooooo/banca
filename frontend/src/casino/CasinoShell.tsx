import type { CSSProperties, ReactNode } from 'react'
import { OfflineNote } from './AppNotes'

// Few, slow and faint: the room should be felt more than seen. Positions are
// fixed rather than random so the page looks the same on every load.
const PARTICLES = [
  { left: '8%', top: '78%', size: 2, float: 26, delay: 0, sway: 18, peak: 0.35, color: '#f1c75b' },
  { left: '19%', top: '42%', size: 3, float: 31, delay: -9, sway: -14, peak: 0.25, color: '#f5f1e8' },
  { left: '31%', top: '88%', size: 2, float: 23, delay: -4, sway: 22, peak: 0.3, color: '#2fb893' },
  { left: '44%', top: '30%', size: 2, float: 29, delay: -15, sway: -20, peak: 0.28, color: '#f1c75b' },
  { left: '57%', top: '70%', size: 3, float: 34, delay: -21, sway: 16, peak: 0.22, color: '#f5f1e8' },
  { left: '68%', top: '50%', size: 2, float: 25, delay: -7, sway: -18, peak: 0.32, color: '#2fb893' },
  { left: '79%', top: '85%', size: 2, float: 28, delay: -12, sway: 12, peak: 0.3, color: '#f1c75b' },
  { left: '88%', top: '36%', size: 3, float: 32, delay: -18, sway: -24, peak: 0.24, color: '#f5f1e8' },
  { left: '94%', top: '66%', size: 2, float: 24, delay: -2, sway: 14, peak: 0.3, color: '#f1c75b' },
  { left: '50%', top: '94%', size: 2, float: 30, delay: -25, sway: -10, peak: 0.26, color: '#2fb893' },
]

/**
 * The room every Banca game is played in: felt, light, dust and a vignette,
 * with the game laid on top. Poker, blackjack and roulette share it, which is
 * what makes them feel like one place.
 */
export function CasinoShell({ children, showdown = false }: { children: ReactNode; showdown?: boolean }) {
  return (
    <div data-showdown={showdown} className="relative min-h-dvh overflow-hidden">
      <div aria-hidden className="casino-bg">
        <div className="casino-bg__light casino-bg__light--table" />
        <div className="casino-bg__light casino-bg__light--gold" />
        <div className="casino-bg__light casino-bg__light--emerald" />
        <div className="casino-bg__noise" />
        {PARTICLES.map((p, index) => (
          <span
            key={index}
            className="particle"
            style={
              {
                left: p.left,
                top: p.top,
                width: p.size,
                height: p.size,
                background: p.color,
                '--float': `${p.float}s`,
                '--delay': `${p.delay}s`,
                '--sway': `${p.sway}px`,
                '--peak': p.peak,
              } as CSSProperties
            }
          />
        ))}
        <div className="casino-bg__vignette" />
      </div>

      <main
        className="relative mx-auto flex min-h-dvh w-full max-w-3xl flex-col px-3 sm:px-6"
        style={{
          paddingTop: 'max(0.75rem, env(safe-area-inset-top))',
          paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))',
        }}
      >
        {children}
      </main>

      <OfflineNote />

      {/* Chips in flight are drawn here, above everything and outside the layout. */}
      <div id="flight-layer" aria-hidden />
    </div>
  )
}
