import { useEffect, useState } from 'react'
import type { Dashboard } from '../player/types'
import { DEFAULTS, type Kind, type Progress } from './catalogue'

// Which card back and felt the player has chosen. Kept on this device, like
// the sound setting: it changes only what they see, and nobody else's table.

export type TableStyle = Record<Kind, string>

const KEY = 'banca.style'
const CHANGED = 'banca:style'

export function readTableStyle(): TableStyle {
  try {
    const kept = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<TableStyle>
    return {
      cards: typeof kept.cards === 'string' ? kept.cards : DEFAULTS.cards,
      felt: typeof kept.felt === 'string' ? kept.felt : DEFAULTS.felt,
    }
  } catch {
    return { ...DEFAULTS }
  }
}

/** Dresses the page in a style. The stylesheet does the rest, from these two words on the root. */
export function wear(style: TableStyle) {
  const root = document.documentElement
  root.dataset.cards = style.cards
  root.dataset.felt = style.felt
  // The bar a phone draws round the page takes the room's darkest colour.
  const colour = getComputedStyle(root).getPropertyValue('--room-4').trim()
  if (colour) document.querySelector('meta[name="theme-color"]')?.setAttribute('content', colour)
}

export function keepTableStyle(style: TableStyle) {
  try {
    localStorage.setItem(KEY, JSON.stringify(style))
  } catch {
    // A browser that will keep nothing still shows the choice until the page is closed.
  }
  wear(style)
  window.dispatchEvent(new Event(CHANGED))
}

/** The chosen style, kept in step with wherever it is changed. */
export function useTableStyle(): [TableStyle, (style: TableStyle) => void] {
  const [style, setStyle] = useState(readTableStyle)
  useEffect(() => {
    const changed = () => setStyle(readTableStyle())
    window.addEventListener(CHANGED, changed)
    return () => window.removeEventListener(CHANGED, changed)
  }, [])
  return [style, keepTableStyle]
}

/** What a player has done that opens things, read off their dashboard. */
export function progressOf(dashboard: Dashboard): Progress {
  return {
    level: dashboard.player.level,
    league: dashboard.league,
    trophyLeagues: dashboard.trophies.map((trophy) => trophy.league),
    achievements: dashboard.achievements.filter((achievement) => achievement.earned).length,
  }
}
