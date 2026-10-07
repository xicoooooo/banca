import { useEffect, useRef } from 'react'

/**
 * What every panel that opens over a table needs: focus moves to its close
 * button when it opens and back to where it was when it closes, Tab stays
 * inside it, and Escape closes it.
 *
 * This happens once, when the panel opens. The screen behind a panel redraws
 * often, several times a second where there is a clock, and hands over a new
 * [onClose] each time; if that restarted this, focus would be pulled back to
 * the close button again and again, out of whatever the player was typing in.
 */
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Keeps Tab inside [panel]: from its last control it goes round to its first,
 * and back the other way. Without this, someone using a keyboard tabs out of
 * an open panel into the table hidden behind it.
 */
export function keepTabInside(panel: Element | null | undefined, event: KeyboardEvent) {
  if (event.key !== 'Tab' || !panel) return
  const stops = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((stop) => stop.offsetParent !== null)
  if (stops.length === 0) return

  const first = stops[0]
  const last = stops[stops.length - 1]
  const at = document.activeElement
  if (!panel.contains(at)) {
    event.preventDefault()
    first.focus()
  } else if (event.shiftKey && at === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && at === last) {
    event.preventDefault()
    first.focus()
  }
}

export function useDialog(onClose: () => void) {
  const closeButton = useRef<HTMLButtonElement>(null)

  const latestOnClose = useRef(onClose)
  useEffect(() => {
    latestOnClose.current = onClose
  })

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    closeButton.current?.focus()

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') latestOnClose.current()
      keepTabInside(closeButton.current?.closest('[role="dialog"]'), event)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      before?.focus?.()
    }
  }, [])

  return closeButton
}
