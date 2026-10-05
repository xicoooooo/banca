/** Whether the person has asked their device for less movement. */
export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * A small offset between -range and +range that is always the same for the
 * same seed. Real cards never land identically, but a card must not twitch to
 * a new angle every time the table redraws, so the variation is fixed per card
 * rather than random.
 */
export function variance(seed: number, range: number): number {
  const wave = Math.sin(seed * 12.9898 + 78.233) * 43758.5453
  return (wave - Math.floor(wave) - 0.5) * 2 * range
}
