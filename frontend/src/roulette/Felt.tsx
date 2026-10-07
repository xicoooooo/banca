import type { CSSProperties, KeyboardEvent } from 'react'
import { ROWS, amountOn, colorOf, numbersOf, spotOf, type Bets, type Spot } from './layout'

type FeltProps = {
  bets: Bets
  /** The pocket to light up, once a spin's result is being shown. */
  landed: number | null
  disabled: boolean
  onPlace: (spot: Spot) => void
  /** In a shared room: what other players have on each spot. */
  others?: Record<Spot, number>
}

const EVEN_MONEY: { spot: Spot; label: string; color?: 'red' | 'black' }[] = [
  { spot: 'low', label: '1–18' },
  { spot: 'even', label: 'Even' },
  { spot: 'red', label: 'Red', color: 'red' },
  { spot: 'black', label: 'Black', color: 'black' },
  { spot: 'odd', label: 'Odd' },
  { spot: 'high', label: '19–36' },
]

/** A bet's amount, short enough for the smallest box: 50, 150, 1.2k. */
const ARROWS: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }

/**
 * Moves focus across the layout with the arrow keys, to the nearest box in
 * the direction pressed. The layout is some fifty buttons, which is a long way
 * to go by Tab alone.
 */
function moveByArrow(event: KeyboardEvent<HTMLDivElement>) {
  const direction = ARROWS[event.key]
  const from = document.activeElement
  if (!direction || !(from instanceof HTMLElement) || !event.currentTarget.contains(from)) return

  const centre = (box: Element) => {
    const rect = box.getBoundingClientRect()
    return [rect.left + rect.width / 2, rect.top + rect.height / 2]
  }
  const [x, y] = centre(from)
  const [dx, dy] = direction

  let nearest: HTMLElement | null = null
  let nearestDistance = Infinity
  for (const box of event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled)')) {
    if (box === from) continue
    const [bx, by] = centre(box)
    const along = (bx - x) * dx + (by - y) * dy
    const across = Math.abs((bx - x) * dy) + Math.abs((by - y) * dx)
    // Only boxes that lie that way, and more that way than off to the side.
    if (along <= 1 || across > along) continue
    const distance = along + across * 2
    if (distance < nearestDistance) {
      nearest = box
      nearestDistance = distance
    }
  }
  if (nearest) {
    event.preventDefault()
    nearest.focus()
  }
}

function short(amount: number): string {
  return amount >= 1000 ? `${Math.round(amount / 100) / 10}k` : String(amount)
}

/**
 * The betting layout, drawn as it is on a real table: zero at one end, three
 * rows of twelve numbers, and the outside bets around them. Pressing a box
 * puts the chosen chip on it.
 */
export function Felt({ bets, landed, disabled, onPlace, others = {} }: FeltProps) {
  const box = (spot: Spot, label: string, style: CSSProperties, color?: string, name?: string) => {
    const amount = amountOn(bets, spot)
    // The number the ball found is always lit; any other box only if chips on it were paid.
    const covers = landed !== null && numbersOf(spot).includes(landed)
    const won = covers && (amount > 0 || spot.startsWith('straight'))
    return (
      <button
        key={spot}
        type="button"
        className="spot"
        style={style}
        data-color={color}
        data-won={won}
        data-lost={landed !== null && amount > 0 && !covers}
        disabled={disabled}
        onClick={() => onPlace(spot)}
        aria-label={`${name ?? label}${amount > 0 ? `, ${amount} on it` : ''}${others[spot] ? `, ${others[spot]} from other players` : ''}`}
      >
        <span className="spot__label">{label}</span>
        {/* Other players' chips are marked in a corner, so the player's own stay the ones that stand out. */}
        {others[spot] > 0 && <span aria-hidden className="spot__others" />}
        {amount > 0 && (
          <span aria-hidden className="spot__chip figure">
            {short(amount)}
          </span>
        )}
      </button>
    )
  }

  return (
    <div className="roulette-felt" role="group" aria-label="Betting layout. Arrow keys move between bets." onKeyDown={moveByArrow}>
      {box(spotOf('straight', 0), '0', { gridColumn: 1, gridRow: '1 / span 3' }, 'green', 'Zero')}

      {ROWS.map((row, rowIndex) =>
        row.map((number, columnIndex) =>
          box(spotOf('straight', number), String(number), { gridColumn: columnIndex + 2, gridRow: rowIndex + 1 }, colorOf(number), `${number} ${colorOf(number)}`),
        ),
      )}

      {/* The column bets sit at the end of the rows they cover. */}
      {[3, 2, 1].map((column, rowIndex) =>
        box(spotOf('column', column), '2:1', { gridColumn: 14, gridRow: rowIndex + 1 }, undefined, `Column ${column}, pays 2 to 1`),
      )}

      {[1, 2, 3].map((dozen) =>
        box(spotOf('dozen', dozen), `${(dozen - 1) * 12 + 1}–${dozen * 12}`, { gridColumn: `${(dozen - 1) * 4 + 2} / span 4`, gridRow: 4 }, undefined, `Dozen ${dozen}, pays 2 to 1`),
      )}

      {EVEN_MONEY.map((bet, index) =>
        box(bet.spot, bet.label, { gridColumn: `${index * 2 + 2} / span 2`, gridRow: 5 }, bet.color, `${bet.label}, pays even money`),
      )}
    </div>
  )
}
