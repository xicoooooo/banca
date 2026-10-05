import { useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from './motion'

/**
 * A chip count that travels to its new value instead of jumping. The real
 * value is always what a screen reader hears; only the eye sees the journey.
 */
export function AnimatedNumber({
  value,
  delay = 0,
  duration = 480,
}: {
  value: number
  delay?: number
  duration?: number
}) {
  const [shown, setShown] = useState(value)
  const settled = useRef(value)

  useEffect(() => {
    if (settled.current === value || prefersReducedMotion()) {
      settled.current = value
      return
    }

    const from = settled.current
    let frame = 0
    let began: number | null = null

    const timer = setTimeout(() => {
      // A hidden page gets no animation frames, so the count would stop part
      // way and show a number that was never true. Jump to the real one.
      if (document.hidden) {
        setShown(value)
        return
      }
      const step = (now: number) => {
        began ??= now
        const progress = Math.min(1, (now - began) / duration)
        const eased = 1 - Math.pow(1 - progress, 3)
        setShown(Math.round(from + (value - from) * eased))
        if (progress < 1) frame = requestAnimationFrame(step)
      }
      frame = requestAnimationFrame(step)
    }, delay)

    settled.current = value
    return () => {
      clearTimeout(timer)
      cancelAnimationFrame(frame)
    }
  }, [value, delay, duration])

  const display = prefersReducedMotion() ? value : shown

  return (
    <span className="figure">
      <span aria-hidden>{display.toLocaleString('en-US')}</span>
      <span className="sr-only">{value.toLocaleString('en-US')}</span>
    </span>
  )
}
