import { useState } from 'react'
import { prefersReducedMotion } from '../casino/motion'
import { WHEEL_ORDER, colorOf, firstTurn, turnFor } from './layout'
import { SPIN_MS } from './useRoulette'

const STEP = 360 / WHEEL_ORDER.length
const FILL = { green: '#0f8a68', red: '#a8261d', black: '#141a19' }

/** A point on a circle of the given radius, with 0 degrees at the top and angles running clockwise. */
function at(radius: number, degrees: number): [number, number] {
  const radians = ((degrees - 90) * Math.PI) / 180
  return [radius * Math.cos(radians), radius * Math.sin(radians)]
}

/** One pocket: a slice of the ring between two radii, centred on its angle. */
function wedge(index: number, outer: number, inner: number): string {
  const from = index * STEP - STEP / 2
  const to = from + STEP
  const [x1, y1] = at(outer, from)
  const [x2, y2] = at(outer, to)
  const [x3, y3] = at(inner, to)
  const [x4, y4] = at(inner, from)
  return `M${x1} ${y1} A${outer} ${outer} 0 0 1 ${x2} ${y2} L${x3} ${y3} A${inner} ${inner} 0 0 0 ${x4} ${y4} Z`
}

type WheelProps = {
  /** How many spins there have been, so a repeat of the same pocket still turns the wheel. */
  roundNumber: number
  /** The pocket the latest spin landed on, or null before the first. */
  pocket: number | null
  spinning: boolean
}

/**
 * The wheel. It is turned so the winning pocket comes to rest under the ball
 * at the top, which is all the animation there is: the result is the server's,
 * decided before the wheel moves, and the wheel only takes its time arriving.
 */
export function Wheel({ roundNumber, pocket, spinning }: WheelProps) {
  // Where the wheel rests, in degrees. Each spin carries on from the last,
  // always the same way round, so it never appears to wind back.
  const [turn, setTurn] = useState(() => firstTurn(roundNumber, pocket))
  const next = turnFor(turn, roundNumber, pocket)
  if (next !== turn) setTurn(next)

  const landed = pocket !== null && !spinning
  const duration = turn.moved && !prefersReducedMotion() ? SPIN_MS : 0

  return (
    <div className="wheel" data-spinning={spinning} role="img" aria-label={landed ? `The ball is on ${pocket} ${colorOf(pocket)}` : spinning ? 'The wheel is spinning' : 'The wheel is still'}>
      <svg viewBox="-100 -100 200 200" aria-hidden>
        <circle r="99" className="wheel__rim" />
        <g className="wheel__turning" style={{ transform: `rotate(${turn.rotation}deg)`, transitionDuration: `${duration}ms` }}>
          {WHEEL_ORDER.map((number, index) => (
            <g key={number}>
              <path d={wedge(index, 93, 62)} fill={FILL[colorOf(number)]} stroke="#c9a24a" strokeWidth="0.35" />
              <text
                transform={`rotate(${index * STEP}) translate(0 -80)`}
                className="wheel__number"
                textAnchor="middle"
                dominantBaseline="middle"
              >
                {number}
              </text>
            </g>
          ))}
          <circle r="62" className="wheel__cone" />
          {/* Spokes, so the turning can be seen even at the hub. */}
          {[0, 90, 180, 270].map((angle) => (
            <rect key={angle} x="-1.2" y="-58" width="2.4" height="40" rx="1.2" className="wheel__spoke" transform={`rotate(${angle + 45})`} />
          ))}
          <circle r="17" className="wheel__hub" />
        </g>
        {/* The ball rests at the top; the wheel brings the pocket to it. */}
        {pocket !== null && <circle cx="0" cy="-70" r="3.6" className="wheel__ball" />}
      </svg>

      {/* What the ball landed on, said plainly once it has. */}
      <div className="wheel__result" data-shown={landed} data-color={landed ? colorOf(pocket) : undefined}>
        {landed && <span className="figure">{pocket}</span>}
      </div>
    </div>
  )
}
