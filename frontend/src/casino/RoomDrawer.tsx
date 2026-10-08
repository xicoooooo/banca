import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { ChatLine, Phrase, RoomPlayer } from './room'
import { CloseTable, InviteButton, TableCode } from './Invite'
import { sound } from './sound'
import { useDialog } from './useDialog'

type RoomDrawerProps = {
  name: string
  players: RoomPlayer[]
  chat: ChatLine[]
  phrases: Phrase[]
  onSay: (phraseId: string) => void
  /** Sends a message the player has typed. */
  onType: (text: string) => void
  /** The names of players this player has chosen not to hear from, and how to change that. */
  muted: Set<string>
  onMute: (name: string) => void
  onClose: () => void
  /** True for a table a player opened for their own company. It is on no list, so it says so. */
  byInvite?: boolean
  /** A private table's code, shown for anyone who would rather be told it than sent a link. */
  code?: string
  /** True when the table is played with practice chips. */
  practice?: boolean
  /** Closes the table for everyone. Given only to the host of a private table. */
  onEnd?: () => void
}

/** The most a message may be. The server holds to the same limit. */
const MAX_LENGTH = 140

function signed(amount: number): string {
  return amount === 0 ? 'Even' : `${amount > 0 ? '+' : '−'}${Math.abs(amount).toLocaleString('en-US')}`
}

/**
 * Who is at a shared table and what has been said at it, the same at every game. Players can type, or say
 * one of the room's phrases with a single press. Nobody watches over a room,
 * so each player can mute anyone they would rather not hear from.
 */
export function RoomDrawer({ name, players, chat, phrases, onSay, onType, muted, onMute, onClose, byInvite = false, code, practice = false, onEnd }: RoomDrawerProps) {
  const [draft, setDraft] = useState('')

  const sendDraft = (event: FormEvent) => {
    event.preventDefault()
    const text = draft.trim()
    if (!text) return
    onType(text)
    setDraft('')
  }

  const heard = chat.filter((line) => !muted.has(line.from))
  const closeButton = useDialog(onClose)
  const end = useRef<HTMLDivElement>(null)

  // The newest line is the one to see.
  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end' })
  }, [heard.length])

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
          <div className="invite-row">
            <p className="text-xs leading-relaxed text-muted">
              {byInvite
                ? 'This table is on no list. Only people you give the link to can find it.'
                : 'Want company? Anyone you send the link to lands at this table.'}
            </p>
            <InviteButton table={name} prominent={byInvite && players.length <= 1} />
          </div>
          {byInvite && code && (
            <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 pb-3">
              <TableCode id={code} />
              {onEnd && <CloseTable onClose={onEnd} />}
            </div>
          )}
          {practice && <p className="label pb-3 leading-relaxed text-gold/80!">Practice chips · nothing here touches anyone's own</p>}

          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0 pb-3">
            {players.map((player, index) => (
              <li key={`${player.name}-${index}`} className="room-player" data-you={player.you} data-muted={muted.has(player.name)}>
                <span className="truncate">{player.you ? 'You' : player.name}</span>
                {player.net !== null ? (
                  <span className="figure font-semibold" data-tone={player.net > 0 ? 'gain' : player.net < 0 ? 'loss' : 'even'}>
                    {signed(player.net)}
                  </span>
                ) : (
                  player.staked > 0 && <span className="figure text-gold-bright">{player.staked.toLocaleString('en-US')}</span>
                )}
                {!player.you && (
                  <button
                    type="button"
                    className="room-player__mute"
                    onClick={() => onMute(player.name)}
                    aria-pressed={muted.has(player.name)}
                    aria-label={muted.has(player.name) ? `Hear from ${player.name} again` : `Mute ${player.name}`}
                  >
                    {muted.has(player.name) ? 'Muted' : 'Mute'}
                  </button>
                )}
              </li>
            ))}
          </ul>

          <div className="chat-log" aria-live="polite" aria-label="What has been said">
            {heard.length === 0 && <p className="py-6 text-center text-sm text-muted">Nobody has said anything yet. Say hello.</p>}
            {heard.map((line, index) => (
              <p key={index} className="chat-line">
                <span className="label tracking-[0.08em]!">{line.from}</span>
                <span className={line.emote ? 'text-2xl leading-none' : 'text-sm text-ivory'}>{line.text}</span>
              </p>
            ))}
            <div ref={end} />
          </div>

          <form onSubmit={sendDraft} className="flex gap-2 pt-3">
            <input
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              maxLength={MAX_LENGTH}
              placeholder="Say something to the room"
              aria-label="Your message"
              enterKeyHint="send"
              autoComplete="off"
              className="name-field min-w-0 flex-1 text-sm!"
            />
            <button type="submit" className="btn btn--call px-4! text-sm" disabled={draft.trim() === ''}>
              Send
            </button>
          </form>

          <div className="flex flex-wrap gap-1.5 pt-2.5" role="group" aria-label="Or say it with one press">
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
