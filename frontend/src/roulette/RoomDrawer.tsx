import { useEffect, useRef } from 'react'
import { sound } from '../casino/sound'
import type { ChatLine, Phrase, RoomPlayer } from './types'

type RoomDrawerProps = {
  name: string
  players: RoomPlayer[]
  chat: ChatLine[]
  phrases: Phrase[]
  onSay: (phraseId: string) => void
  onClose: () => void
}

function signed(amount: number): string {
  return amount === 0 ? 'Even' : `${amount > 0 ? '+' : '−'}${Math.abs(amount).toLocaleString('en-US')}`
}

/**
 * Who is in the room and what has been said in it. Players talk by choosing
 * from the room's phrases rather than typing, which keeps a room of strangers
 * friendly without anyone having to watch over it.
 */
export function RoomDrawer({ name, players, chat, phrases, onSay, onClose }: RoomDrawerProps) {
  const closeButton = useRef<HTMLButtonElement>(null)
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    closeButton.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      before?.focus?.()
    }
  }, [onClose])

  // The newest line is the one to see.
  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end' })
  }, [chat.length])

  const words = phrases.filter((phrase) => !phrase.emote)
  const emotes = phrases.filter((phrase) => phrase.emote)

  const say = (id: string) => {
    sound.click()
    onSay(id)
  }

  return (
    <div className="dossier-backdrop" onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-label={name} onClick={(event) => event.stopPropagation()} className="dossier">
        <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-3">
          <div>
            <p className="label text-gold!">
              {players.length} {players.length === 1 ? 'player' : 'players'} at the table
            </p>
            <h2 className="pt-1.5 text-xl font-semibold tracking-tight text-ivory">{name}</h2>
          </div>
          <button ref={closeButton} type="button" onClick={onClose} className="btn btn--quiet px-3!">
            Close
          </button>
        </header>

        <div className="flex min-h-0 flex-1 flex-col px-5" style={{ paddingBottom: 'max(1.25rem, env(safe-area-inset-bottom))' }}>
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0 pb-3">
            {players.map((player, index) => (
              <li key={`${player.name}-${index}`} className="room-player" data-you={player.you}>
                <span className="truncate">{player.you ? 'You' : player.name}</span>
                {player.net !== null ? (
                  <span className="figure font-semibold" data-tone={player.net > 0 ? 'gain' : player.net < 0 ? 'loss' : 'even'}>
                    {signed(player.net)}
                  </span>
                ) : (
                  player.staked > 0 && <span className="figure text-gold-bright">{player.staked.toLocaleString('en-US')}</span>
                )}
              </li>
            ))}
          </ul>

          <div className="chat-log" aria-live="polite" aria-label="What has been said">
            {chat.length === 0 && <p className="py-6 text-center text-sm text-muted">Nobody has said anything yet. Say hello.</p>}
            {chat.map((line, index) => (
              <p key={index} className="chat-line">
                <span className="label tracking-[0.08em]!">{line.from}</span>
                <span className={line.emote ? 'text-2xl leading-none' : 'text-sm text-ivory'}>{line.text}</span>
              </p>
            ))}
            <div ref={end} />
          </div>

          <div className="flex flex-wrap gap-1.5 pt-3" role="group" aria-label="Say something">
            {words.map((phrase) => (
              <button key={phrase.id} type="button" className="phrase" onClick={() => say(phrase.id)}>
                {phrase.text}
              </button>
            ))}
          </div>
          <div className="flex gap-1.5 pt-1.5" role="group" aria-label="React">
            {emotes.map((phrase) => (
              <button key={phrase.id} type="button" className="phrase phrase--emote" onClick={() => say(phrase.id)} aria-label={`React with ${phrase.text}`}>
                {phrase.text}
              </button>
            ))}
          </div>
        </div>
      </section>
    </div>
  )
}
