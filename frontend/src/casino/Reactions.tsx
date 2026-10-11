import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { variance } from './motion'
import type { Reaction } from './reactionStream'
import type { Phrase } from './room'
import { sound } from './sound'

/** Where a reaction starts from: the seat of whoever threw it, if they have one on screen. */
function startOf(reaction: Reaction): { left: string; top: string } {
  const seat = document.querySelector(`[data-seat-of="${CSS.escape(reaction.from)}"]`)?.getBoundingClientRect()
  if (seat && seat.width > 0) {
    return { left: `${seat.left + seat.width / 2 + variance(reaction.id, 14)}px`, top: `${seat.top + seat.height * 0.35}px` }
  }
  // Nobody to pin it to, as in a roulette room: it rises from the side, each a little apart from the last.
  return { left: `${14 + (reaction.id % 4) * 9}%`, top: '58%' }
}

function Thrown({ reaction }: { reaction: Reaction }) {
  // Worked out once, when it is thrown, so it does not follow a seat that moves.
  const [start] = useState(() => startOf(reaction))
  return (
    <span className="reaction" style={{ ...start, '--tilt': `${variance(reaction.id + 7, 10)}deg` } as CSSProperties}>
      <span className="reaction__emoji">{reaction.text}</span>
      <span className="reaction__from">{reaction.from}</span>
    </span>
  )
}

/** The reactions on the table, floating up from whoever threw them. Those of muted players are not shown. */
export function Reactions({ reactions, muted }: { reactions: Reaction[]; muted: Set<string> }) {
  const shown = reactions.filter((reaction) => !muted.has(reaction.from))
  const latest = shown.at(-1)
  return (
    <div className="reactions">
      <div aria-hidden>
        {shown.map((reaction) => (
          <Thrown key={reaction.id} reaction={reaction} />
        ))}
      </div>
      <p className="sr-only" role="status">
        {latest ? `${latest.from}: ${latest.text}` : ''}
      </p>
    </div>
  )
}

/** A moment between one reaction and the next, a little longer than the server asks for. */
const BETWEEN_MS = 1_700

/**
 * The way to throw a reaction without opening the chat: a button that offers
 * the room's emotes in a row.
 */
export function ReactionButton({ phrases, onSay }: { phrases: Phrase[]; onSay: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  const [resting, setResting] = useState(false)
  const holder = useRef<HTMLDivElement>(null)
  const emotes = phrases.filter((phrase) => phrase.emote)

  useEffect(() => {
    if (!open) return
    const away = (event: Event) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !holder.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', away)
    document.addEventListener('keydown', away)
    return () => {
      document.removeEventListener('pointerdown', away)
      document.removeEventListener('keydown', away)
    }
  }, [open])

  useEffect(() => {
    if (!resting) return
    const timer = setTimeout(() => setResting(false), BETWEEN_MS)
    return () => clearTimeout(timer)
  }, [resting])

  if (emotes.length === 0) return null

  return (
    <div ref={holder} className="react-button">
      <button
        type="button"
        className="icon-button"
        aria-label="React"
        aria-expanded={open}
        disabled={resting}
        onClick={() => {
          sound.click()
          setOpen((was) => !was)
        }}
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <circle cx="12" cy="12" r="9" />
          <path d="M8.5 14.5a4.5 4.5 0 007 0M9 9.5h.01M15 9.5h.01" />
        </svg>
      </button>
      {open && (
        <div className="react-button__row" role="group" aria-label="Reactions">
          {emotes.map((emote) => (
            <button
              key={emote.id}
              type="button"
              className="react-button__emote"
              aria-label={`React with ${emote.text}`}
              onClick={() => {
                onSay(emote.id)
                setOpen(false)
                setResting(true)
              }}
            >
              {emote.text}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
