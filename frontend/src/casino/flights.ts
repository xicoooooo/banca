import { CHIP_CLASS, type ChipColor } from './chips'
import { prefersReducedMotion } from './motion'

/**
 * Sends chips across the table from one named place to another.
 *
 * Places are elements marked with `data-anchor`, such as a player's stack or
 * the pot. The chips are drawn in a layer of their own and removed when they
 * land, so a flight never touches layout and never holds up the game: it is
 * decoration on top of state that has already changed.
 */
export function flyChips(from: string, to: string, colors: ChipColor[], delay = 0): void {
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

  colors.forEach((color, index) => {
    const chip = document.createElement('span')
    chip.className = `${CHIP_CLASS[color]} chip--flying`
    chip.style.left = `${startX}px`
    chip.style.top = `${startY}px`
    chip.style.setProperty('--chip', '20px')
    layer.append(chip)

    // Each chip takes a slightly different line, the way thrown chips do.
    const spread = (index - (colors.length - 1) / 2) * 5
    const spin = 200 + index * 70
    const landed = `translate3d(${dx + spread}px, ${dy}px, 0) rotate(${spin}deg)`

    const flight = chip.animate(
      [
        { transform: 'translate3d(0, 0, 0) rotate(0deg) scale(0.85)', opacity: 0 },
        { opacity: 1, offset: 0.12 },
        { transform: `${landed} scale(1)`, opacity: 1, offset: 0.78 },
        { transform: `${landed} scale(1.12)`, opacity: 1, offset: 0.88 },
        { transform: `${landed} scale(1)`, opacity: 0 },
      ],
      { duration: 560, delay: delay + index * 60, easing: 'cubic-bezier(0.2, 0.7, 0.2, 1)', fill: 'both' },
    )
    flight.onfinish = () => chip.remove()
    flight.oncancel = () => chip.remove()
  })
}
