import { useEffect, useRef } from 'react'

/**
 * What every panel that opens over a table needs: focus moves to its close
 * button when it opens and back to where it was when it closes, and Escape
 * closes it.
 *
 * This happens once, when the panel opens. The screen behind a panel redraws
 * often, several times a second where there is a clock, and hands over a new
 * [onClose] each time; if that restarted this, focus would be pulled back to
 * the close button again and again, out of whatever the player was typing in.
 */
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
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      before?.focus?.()
    }
  }, [])

  return closeButton
}
