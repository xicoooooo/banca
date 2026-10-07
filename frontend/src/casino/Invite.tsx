import { useState } from 'react'
import { sound } from './sound'

type Said = 'idle' | 'copied' | 'failed'

/**
 * Hands the table's address to whoever the player wants at it. On a phone
 * that is the share sheet; elsewhere the address is copied, and the player
 * told that it has been.
 */
export function InviteButton({ table, prominent = false }: { table: string; prominent?: boolean }) {
  const [said, setSaid] = useState<Said>('idle')

  const invite = async () => {
    sound.click()
    const url = window.location.href
    const words = { title: 'Banca', text: `Join me at ${table} on Banca. Play money only.`, url }

    if (typeof navigator.share === 'function') {
      try {
        await navigator.share(words)
        return
      } catch (problem) {
        // Closing the share sheet is a choice, not a failure. Anything else falls through to copying.
        if (problem instanceof DOMException && problem.name === 'AbortError') return
      }
    }
    try {
      await navigator.clipboard.writeText(url)
      setSaid('copied')
    } catch {
      setSaid('failed')
    }
    setTimeout(() => setSaid('idle'), 2600)
  }

  return (
    <button type="button" className={`btn ${prominent ? 'btn--call' : 'btn--quiet'} px-4! py-2! text-sm`} onClick={invite} aria-live="polite">
      {said === 'copied' ? 'Link copied' : said === 'failed' ? 'Copy it from the address bar' : 'Invite friends'}
    </button>
  )
}
