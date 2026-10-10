import { useEffect, useState } from 'react'
import { sound } from '../casino/sound'
import { useDialog } from '../casino/useDialog'
import { pictureOf } from './drawHandCard'
import type { HandCard } from './handCard'

/** The way to a hand's picture: a small button beside the result. */
export function ShareHandButton({ onOpen }: { onOpen: () => void }) {
  return (
    <button
      type="button"
      className="btn btn--fold px-4!"
      aria-label="Share this hand"
      onClick={() => {
        sound.click()
        onOpen()
      }}
    >
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M12 15V3m0 0L7.5 7.5M12 3l4.5 4.5M5 12v6a2 2 0 002 2h10a2 2 0 002-2v-6" />
      </svg>
    </button>
  )
}

/**
 * A finished hand as a picture, shown before it goes anywhere, with the ways
 * to send it on. The picture is made on this device. Sharing hands it to the
 * phone's own share sheet; where there is none, it can be saved instead.
 */
export function ShareHandPanel({ card, onClose }: { card: HandCard; onClose: () => void }) {
  const closeButton = useDialog(onClose)
  const [picture, setPicture] = useState<{ file: File; url: string } | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let url: string | null = null
    let gone = false
    pictureOf(card).then((file) => {
      if (gone) return
      if (!file) return setFailed(true)
      url = URL.createObjectURL(file)
      setPicture({ file, url })
    })
    return () => {
      gone = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [card])

  const canShare = picture !== null && typeof navigator.canShare === 'function' && navigator.canShare({ files: [picture.file] })

  const share = async () => {
    if (!picture) return
    sound.click()
    try {
      await navigator.share({ files: [picture.file], title: 'A hand at Banca', text: `${card.headline}. ${location.origin}` })
    } catch {
      // Sharing was called off, which is the player's business.
    }
  }

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label="Share this hand" onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
          <div>
            <p className="label text-gold!">Hand {card.handNumber}</p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">Share this hand</h2>
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="flex flex-col items-center gap-4 overflow-y-auto px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          {picture ? (
            <img src={picture.url} alt={`${card.headline}. ${card.detail}.`} className="hand-card" />
          ) : (
            <div className="hand-card hand-card--waiting" role="status">
              <p className="label">{failed ? 'This browser cannot make the picture' : 'Drawing the hand'}</p>
            </div>
          )}

          {picture && (
            <div className="flex w-full gap-2.5">
              <a href={picture.url} download={picture.file.name} className={`btn flex-1 ${canShare ? 'btn--fold' : 'btn--raise'}`} onClick={() => sound.click()}>
                Save picture
              </a>
              {canShare && (
                <button type="button" className="btn btn--raise flex-1" onClick={share}>
                  Share
                </button>
              )}
            </div>
          )}
          <p className="text-center text-xs leading-relaxed text-muted">
            The picture is made on your device. It shows the cards, the result and the names at the table, and nothing about your chips.
          </p>
        </div>
      </section>
    </div>
  )
}
