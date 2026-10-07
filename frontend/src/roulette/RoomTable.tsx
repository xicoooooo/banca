import { useCallback, useEffect, useState } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { CasinoShell } from '../casino/CasinoShell'
import { OutOfChips, StakedNote } from '../casino/ChipNotices'
import { useInviteOffer } from '../casino/useInviteOffer'
import { RoomDrawer } from '../casino/RoomDrawer'
import { ConnectionNote } from '../casino/ConnectionNote'
import { Header, type Status } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { sound } from '../casino/sound'
import { useSecondsUntil } from '../casino/useSecondsUntil'
import { AnalystPanel, AnalystPill } from './Analyst'
import { ChipPicker } from './ChipPicker'
import { Felt } from './Felt'
import { NO_BETS, betsFrom, colorOf, outlook, place, spotOf, totalOf, undo, wagersOf, type Bets, type Spot } from './layout'
import type { RoomView } from './types'
import { useRoom } from './useRoom'
import { Wheel } from './Wheel'

function statusOf(view: RoomView): Status {
  // Kept short: the room's name shares the header with it.
  if (view.phase === 'betting') return { text: 'Bets open', tone: 'gold' }
  if (view.phase === 'spinning') return { text: 'No more bets', tone: 'emerald' }
  return { text: 'Next round', tone: 'quiet' }
}

/**
 * A shared roulette room. The room keeps the time for everyone in it: bets are
 * open for a while, then the wheel turns once for all of them. Chips go down
 * as they are pressed, with no button to spin, and every bet is sent to the
 * room as it is made so that it stands if the connection drops.
 */
export function RoomTable({ roomId, onLeave }: { roomId: string; onLeave?: () => void }) {
  const { view, endsAt, phaseMs, connection, error, refusals, send, chat, phrases, reading, askAbout, forgetRead, broke, staked, retry } = useRoom(roomId)
  const seconds = useSecondsUntil(endsAt)
  const [bets, setBets] = useState<Bets>(NO_BETS)
  const [previous, setPrevious] = useState<Bets>(NO_BETS)
  const [chip, setChip] = useState(10)
  const [showRead, setShowRead] = useState(false)
  const [showRoom, setShowRoom] = useState(false)
  useInviteOffer(view?.byInvite === true && view.players.length <= 1, useCallback(() => setShowRoom(true), []))
  const [heard, setHeard] = useState(0)
  // Who this player would rather not hear from. Kept on this device only, for this visit.
  const [muted, setMuted] = useState<Set<string>>(new Set())
  const mute = (name: string) =>
    setMuted((current) => {
      const next = new Set(current)
      if (!next.delete(name)) next.add(name)
      return next
    })

  // A new round clears the felt, and the layout just played is kept to be put down again.
  const round = view?.roundNumber ?? 0
  const [roundSeen, setRoundSeen] = useState(0)
  if (round !== roundSeen) {
    setRoundSeen(round)
    if (totalOf(bets) > 0) setPrevious(bets)
    setBets(view ? betsFrom(view.bets) : NO_BETS)
    forgetRead()
  }

  // When the room refuses a layout, or the player sits back down, the felt shows what the room holds.
  const [refusalsSeen, setRefusalsSeen] = useState(refusals)
  if (refusals !== refusalsSeen) {
    setRefusalsSeen(refusals)
    if (view) setBets(betsFrom(view.bets))
  }

  // The newest thing said is shown for a moment where the hint usually is.
  const latest = chat.findLast((line) => !muted.has(line.from))
  const [quietAfter, setQuietAfter] = useState(0)
  const spoken = chat.length > quietAfter
  useEffect(() => {
    if (chat.length === 0) return
    const timer = setTimeout(() => setQuietAfter(chat.length), 4_000)
    return () => clearTimeout(timer)
  }, [chat.length])

  if (!view) {
    return (
      <CasinoShell>
        <Header detail="Roulette" onLeave={onLeave} />
        {connection === 'gone' ? (
          <Loading failed message="This room has closed. Ask for a new link, or open a room of your own." />
        ) : connection === 'closed' || connection === 'replaced' ? (
          <Loading
            failed
            message={connection === 'replaced' ? 'This room is open somewhere else.' : 'Could not reach the room. Try again in a minute.'}
          />
        ) : (
          <Loading message="Finding you a place" />
        )}
      </CasinoShell>
    )
  }

  const betting = view.phase === 'betting'
  const spinning = view.phase === 'spinning'
  const shown = view.phase === 'results' ? view.result : null
  const total = totalOf(bets)
  const limits = { minBet: view.minBet, maxInside: view.maxInside, maxOutside: view.maxOutside, stack: view.stack + totalOf(betsFrom(view.bets)) }
  const { best, covered } = outlook(bets)

  // What others have down: the room's total on each spot, less the player's own.
  const others: Record<Spot, number> = {}
  for (const spot of view.crowd) {
    const key = spotOf(spot.kind, spot.number ?? undefined)
    const theirs = spot.amount - view.bets.filter((bet) => spotOf(bet.kind, bet.number ?? undefined) === key).reduce((sum, bet) => sum + bet.amount, 0)
    if (theirs > 0) others[key] = theirs
  }

  const change = (next: Bets) => {
    setBets(next)
    forgetRead()
    send({ type: 'bets', bets: wagersOf(next) })
  }

  const put = (spot: Spot) => {
    if (!betting) return
    const next = place(bets, spot, chip, limits)
    if (next === bets) return
    sound.chipClink()
    change(next)
  }

  const canRebet = betting && total === 0 && totalOf(previous) > 0 && totalOf(previous) <= limits.stack
  const unread = chat.length - heard

  return (
    <CasinoShell showdown={spinning}>
      <Header detail={`${view.name} · ${view.players.length}`} status={statusOf(view)} onLeave={onLeave} />

      <div className="felt mt-2.5 flex flex-1 flex-col">
        <div className="flex flex-1 flex-col items-center justify-evenly gap-2 px-2 py-3 short:py-2">
          <div className="flex w-full items-center justify-center gap-3">
            <Wheel roundNumber={view.roundNumber} pocket={view.pocket} spinning={spinning} />

            <div className="flex w-36 flex-none flex-col items-start gap-2">
              <div aria-live="polite" className="min-h-12">
                {view.phase === 'results' && view.pocket !== null ? (
                  <div className="rise-in">
                    <p className="label">{view.pocket === 0 ? 'Zero' : `${view.pocket} ${colorOf(view.pocket)}`}</p>
                    {shown ? (
                      <p className="figure text-xl leading-tight font-semibold" data-tone={shown.net > 0 ? 'gain' : shown.net < 0 ? 'loss' : 'even'}>
                        {shown.net > 0 ? `+${shown.net.toLocaleString('en-US')}` : shown.net < 0 ? `−${Math.abs(shown.net).toLocaleString('en-US')}` : 'Even'}
                      </p>
                    ) : (
                      <p className="label pt-1 text-gold/80!">You sat this one out</p>
                    )}
                  </div>
                ) : (
                  <p className="label leading-relaxed text-gold/80!">
                    {spinning ? 'No more bets' : 'One wheel'}
                    <br />
                    {spinning ? '' : 'for the whole room'}
                  </p>
                )}
              </div>

              <div className="glass glass--strong rounded-2xl px-3 py-1.5">
                <span className="label block">You</span>
                <span className="block text-base font-semibold text-ivory">
                  <AnimatedNumber value={view.stack} />
                </span>
              </div>

              {betting && !broke && (
                <AnalystPill reading={reading} canAsk={total > 0} onAsk={() => askAbout(wagersOf(bets))} onOpen={() => setShowRead(true)} />
              )}
            </div>
          </div>

          {/* Where the room's ball has been, newest first. Not a guide to where it will go. */}
          <ol className="history" aria-label="This room's recent results, newest first">
            {view.history.slice(0, 10).map((pocket, index) => (
              <li key={`${view.history.length}-${index}`} data-color={colorOf(pocket)} className="figure">
                {pocket}
              </li>
            ))}
            {view.history.length === 0 && <li className="history__empty label">No spins yet</li>}
          </ol>

          <Felt bets={bets} landed={view.phase === 'results' ? view.pocket : null} disabled={!betting || broke !== null} onPlace={put} others={others} />
        </div>
      </div>

      <footer className="mt-2.5 flex min-h-36 flex-col justify-end">
        <ConnectionNote connection={connection} />
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}
        <StakedNote amount={staked} />
        {shown?.refilled && <p className="pb-2 text-center text-sm text-gold-bright">Out of chips, so the house has staked you</p>}

        {broke ? (
          <OutOfChips broke={broke} onRetry={retry} onLeave={onLeave} />
        ) : (
          <div className="flex flex-col gap-2.5">
            <div className="flex items-center justify-between gap-2">
              <ChipPicker chip={chip} onPick={setChip} />

              <div className="flex gap-1.5">
                <button type="button" className="icon-button" onClick={() => change(undo(bets))} disabled={!betting || total === 0} aria-label="Take back the last chip">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M9 14L4 9l5-5M4 9h10a6 6 0 010 12h-3" />
                  </svg>
                </button>
                <button type="button" className="icon-button" onClick={() => change(NO_BETS)} disabled={!betting || total === 0} aria-label="Clear all your bets">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
                <button
                  type="button"
                  className="icon-button"
                  onClick={() => {
                    sound.click()
                    setHeard(chat.length)
                    setShowRoom(true)
                  }}
                  aria-label={`Open the room: ${view.players.length} here${unread > 0 ? `, ${unread} new messages` : ''}`}
                >
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M20 12a8 8 0 01-11.8 7L4 20l1-4.2A8 8 0 1120 12z" />
                  </svg>
                  {unread > 0 && !showRoom && <span aria-hidden className="icon-button__dot" />}
                </button>
              </div>
            </div>

            {spoken && latest && latest === chat.at(-1) && !showRoom ? (
              <p className="chat-toast rise-in" key={chat.length}>
                <span className="label tracking-[0.08em]!">{latest.from}</span> {latest.text}
              </p>
            ) : reading.read && betting ? (
              <p role="status" className="coach-reason coach-reason--longer rise-in pb-0!">
                {reading.read.text}
              </p>
            ) : canRebet ? (
              <button type="button" className="btn btn--quiet mx-auto px-4! py-1.5! text-xs" onClick={() => change(previous)}>
                Same bets again · {totalOf(previous).toLocaleString('en-US')}
              </button>
            ) : (
              <p className="label text-center tracking-[0.12em]!" aria-live="polite">
                {!betting
                  ? spinning
                    ? 'The wheel is turning for the whole room'
                    : 'Bets open again in a moment'
                  : total === 0
                    ? 'Pick a chip, then press the layout'
                    : `Wins on ${Math.round(covered * 100)}% of the wheel · up to +${best.toLocaleString('en-US')}`}
              </p>
            )}

            {/* The room's clock, where a private table has its spin button. */}
            <div className="round-clock" data-phase={view.phase} role="timer" aria-live="off">
              <span
                aria-hidden
                className="round-clock__fill"
                key={`${view.roundNumber}-${view.phase}`}
                style={{ animationDuration: `${phaseMs}ms` }}
              />
              <span className="relative">
                {betting
                  ? total > 0
                    ? `${total.toLocaleString('en-US')} down · bets close in ${seconds}`
                    : `Bets close in ${seconds}`
                  : spinning
                    ? 'No more bets'
                    : `Next round in ${seconds}`}
              </span>
            </div>
          </div>
        )}
      </footer>

      {showRead && betting && reading.status !== 'idle' && <AnalystPanel reading={reading} onClose={() => setShowRead(false)} />}
      {showRoom && (
        <RoomDrawer
          name={view.name}
          byInvite={view.byInvite}
          players={view.players}
          chat={chat}
          phrases={phrases}
          onSay={(say) => send({ type: 'chat', say })}
          onType={(text) => send({ type: 'chat', text })}
          muted={muted}
          onMute={mute}
          onClose={() => {
            setHeard(chat.length)
            setShowRoom(false)
          }}
        />
      )}
    </CasinoShell>
  )
}
