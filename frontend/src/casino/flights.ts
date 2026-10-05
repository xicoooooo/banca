import { CHIP_CLASS, type ChipColor } from './chips'
import { prefersReducedMotion } from './motion'

/**
 * Sends chips across the table from one named place to another.
 *
 * Places are elements marked with `data-anchor`, such as a player's stack or
 * the pot. The chips are drawn in a layer of their own and removed when they
 * land, so a flight never touches layout and never holds up the game: it is
 * decoration on top of state that has already changed.
 *
 * A `toss` is a bet thrown forward: each chip leaves at its own moment, on its
 * own arc, and lands a little apart. A `gather` is a pot being pushed to its
 * winner: the chips move as one.
 */
export function flyChips(
  from: string,
  to: string,
  colors: ChipColor[],
  delay = 0,
  style: 'toss' | 'gather' = 'toss',
): void {
  // A hidden page does not run animations, so chips launched there would only
  // queue up and all fly at once when the person came back.
  if (prefersReducedMotion() || document.hidden) return

  const layer = document.getElementById('flight-layer')
  const origin = document.querySelector(`[data-anchor="${from}"]`)
  const target = document.querySelector(`[data-anchor="${to}"]`)
  if (!layer || !origin || !target) return

  const a = origin.getBoundingClientRect()
  const b = target.getBoundingClientRect()
  const startX = a.left + a.width / 2
  const startY = a.top + a.height / 2
  const dx = b.left + b.width / 2 - startX
  const dy = b.top + b.height / 2 - startY
  const tossed = style === 'toss'

  colors.forEach((color, index) => {
    const chip = document.createElement('span')
    chip.className = `${CHIP_CLASS[color]} chip--flying`
    chip.style.left = `${startX}px`
    chip.style.top = `${startY}px`
    chip.style.setProperty('--chip', '20px')
    layer.append(chip)

    const middle = (colors.length - 1) / 2
    const scatter = tossed ? 7 : 3
    const endX = dx + (index - middle) * scatter + (tossed ? (Math.random() - 0.5) * 5 : 0)
    const endY = dy + (tossed ? (Math.random() - 0.5) * 4 : 0)
    // The arc bows sideways to the line of travel, more for a toss.
    const bow = (tossed ? 16 + Math.random() * 12 : 8) * (dx >= 0 ? -1 : 1)
    const spin = tossed ? 180 + index * 75 + Math.random() * 60 : 120

    const at = (x: number, y: number, turn: number, scale: number) =>
      `translate3d(${x}px, ${y}px, 0) rotate(${turn}deg) scale(${scale})`

    const flight = chip.animate(
      [
        { transform: at(0, 0, 0, 0.85), opacity: 0 },
        { opacity: 1, offset: 0.1 },
        { transform: at(endX / 2 + bow, endY / 2, spin / 2, 1.08), opacity: 1, offset: 0.45 },
        { transform: at(endX, endY, spin, 1), opacity: 1, offset: 0.76 },
        // A chip that lands bounces once before it lies still.
        { transform: at(endX, endY - (tossed ? 4 : 2), spin, 1.06), opacity: 1, offset: 0.86 },
        { transform: at(endX, endY, spin, 1), opacity: 1, offset: 0.94 },
        { transform: at(endX, endY, spin, 1), opacity: 0 },
      ],
      {
        duration: tossed ? 560 + Math.random() * 110 : 620,
        delay: delay + index * (tossed ? 50 : 38) + (tossed ? Math.random() * 45 : 0),
        easing: 'cubic-bezier(0.2, 0.7, 0.2, 1)',
        fill: 'both',
      },
    )
    flight.onfinish = () => chip.remove()
    flight.oncancel = () => chip.remove()
  })
}
