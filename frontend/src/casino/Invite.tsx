import { useState } from 'react'
import { sound } from './sound'
import { shownCode } from './tableCode'

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

/**
 * A private table's code, for telling someone who cannot be sent a link, or
 * who has Banca on their home screen and nowhere to paste one. Pressing it
 * copies it.
 */
export function TableCode({ id }: { id: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(shownCode(id))
      setCopied(true)
      setTimeout(() => setCopied(false), 2200)
    } catch {
      // It is there to be read, whether or not it can be copied.
    }
  }
  return (
    <button type="button" className="table-code" onClick={copy} aria-label={`Table code ${shownCode(id).split('').join(' ')}. Press to copy.`}>
      <span className="label">{copied ? 'Copied' : 'Table code'}</span>
      <span className="table-code__digits figure">{shownCode(id)}</span>
    </button>
  )
}

/** The host's way of closing a private table. Asked twice, since it sends everyone home. */
export function CloseTable({ onClose }: { onClose: () => void }) {
  const [sure, setSure] = useState(false)
  if (!sure) {
    return (
      <button type="button" className="guide__skip label" onClick={() => setSure(true)}>
        Close the table
      </button>
    )
  }
  return (
    <span className="flex items-center gap-2">
      <button type="button" className="btn btn--fold px-4! py-2! text-sm" onClick={onClose}>
        Close it for everyone
      </button>
      <button type="button" className="btn btn--quiet px-4! py-2! text-sm" onClick={() => setSure(false)}>
        Keep playing
      </button>
    </span>
  )
}
