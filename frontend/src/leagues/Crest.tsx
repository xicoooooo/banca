// The metal each league is named for. Emerald, the highest, is Banca's own colour.
const METAL: Record<string, { light: string; dark: string }> = {
  Bronze: { light: '#e0a877', dark: '#8a5a33' },
  Silver: { light: '#eef1f4', dark: '#8b949e' },
  Gold: { light: '#f8dc8a', dark: '#b8872f' },
  Platinum: { light: '#d8f3f5', dark: '#5fa3ab' },
  Emerald: { light: '#7fe3bf', dark: '#0b604b' },
}

type CrestProps = {
  league: string
  /** How wide to draw it, in pixels. */
  size?: number
  /** Drawn faintly, for a league the player is not in. */
  dim?: boolean
  /** For a trophy: the place it was won for, shown on the shield where the star would be. */
  place?: number
}

/** A league's crest: a shield in its metal. Decoration only; the league is always named in words beside it. */
export function Crest({ league, size = 56, dim = false, place }: CrestProps) {
  const metal = METAL[league] ?? METAL.Bronze
  const id = `crest-${league}`

  return (
    <svg viewBox="0 0 48 56" width={size} height={(size * 56) / 48} aria-hidden style={{ opacity: dim ? 0.3 : 1, flex: 'none' }}>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={metal.light} />
          <stop offset="1" stopColor={metal.dark} />
        </linearGradient>
      </defs>
      <path d="M24 2l19 6v19c0 12.5-8 21-19 27C13 48 5 39.500 5 27V8z" fill={`url(#${id})`} stroke="rgb(0 0 0 / 0.35)" strokeWidth="1" />
      <path d="M24 8l13 4.200V27c0 9-5.500 15.500-13 20-7.500-4.500-13-11-13-20V12.200z" fill="none" stroke="rgb(255 255 255 / 0.45)" strokeWidth="1" />
      {place ? (
        <text x="24" y="35" textAnchor="middle" fontSize="20" fontWeight="800" fill="rgb(0 0 0 / 0.5)">
          {place}
        </text>
      ) : (
        <path d="M24 17l3.100 6.400 7 1-5.050 4.950 1.200 7L24 33l-6.250 3.350 1.200-7L13.900 24.400l7-1z" fill="rgb(0 0 0 / 0.28)" />
      )}
    </svg>
  )
}
