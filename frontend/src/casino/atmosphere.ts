import { useEffect, useState } from 'react'
import type { AmbienceKind } from './roomSound'

// How the room looks and sounds, as the player has chosen. Kept on this
// device, like the sound setting: it changes nothing anyone else sees.

/** The light in the room. Evening is the room as it has always been. */
export type ThemeName = 'evening' | 'candlelit' | 'daylight' | 'smoky'

export type Atmosphere = { theme: ThemeName; ambience: AmbienceKind }

export const THEMES: { name: ThemeName; title: string; detail: string }[] = [
  { name: 'evening', title: 'Evening', detail: 'The room as it has always been' },
  { name: 'candlelit', title: 'Candlelit', detail: 'Warmer and darker, the light low' },
  { name: 'daylight', title: 'Daylight', detail: 'Brighter, with the corners lifted' },
  { name: 'smoky', title: 'Smoky', detail: 'A haze drifting over the tables' },
]

export const AMBIENCES: { name: AmbienceKind; title: string; detail: string }[] = [
  { name: 'off', title: 'Off', detail: 'Only the table' },
  { name: 'room', title: 'The room', detail: 'A quiet hush' },
  { name: 'rain', title: 'Rain', detail: 'On the windows' },
  { name: 'lounge', title: 'Lounge', detail: 'Slow chords' },
]

const KEY = 'banca.atmosphere'
const CHANGED = 'banca:atmosphere'
export const QUIET: Atmosphere = { theme: 'evening', ambience: 'off' }

export function readAtmosphere(): Atmosphere {
  try {
    const kept = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<Atmosphere>
    return {
      theme: THEMES.some((theme) => theme.name === kept.theme) ? kept.theme! : QUIET.theme,
      ambience: AMBIENCES.some((ambience) => ambience.name === kept.ambience) ? kept.ambience! : QUIET.ambience,
    }
  } catch {
    return QUIET
  }
}

/** Lights the room for [theme]. The stylesheet does the rest, from the word on the root. */
export function light(theme: ThemeName) {
  document.documentElement.dataset.theme = theme
}

export function keepAtmosphere(atmosphere: Atmosphere) {
  try {
    localStorage.setItem(KEY, JSON.stringify(atmosphere))
  } catch {
    // A browser that will keep nothing still uses the choice until the page is closed.
  }
  light(atmosphere.theme)
  window.dispatchEvent(new Event(CHANGED))
}

/** The chosen atmosphere, kept in step with wherever it is changed. */
export function useAtmosphere(): [Atmosphere, (atmosphere: Atmosphere) => void] {
  const [atmosphere, setAtmosphere] = useState(readAtmosphere)
  useEffect(() => {
    const changed = () => setAtmosphere(readAtmosphere())
    window.addEventListener(CHANGED, changed)
    return () => window.removeEventListener(CHANGED, changed)
  }, [])
  return [atmosphere, keepAtmosphere]
}
