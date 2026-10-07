import { useState } from 'react'
import { AnimatedNumber } from '../casino/AnimatedNumber'
import { CasinoShell } from '../casino/CasinoShell'
import { OutOfChips, StakedNote } from '../casino/ChipNotices'
import { Header, type Status } from '../casino/Header'
import { ConnectionNote } from '../casino/ConnectionNote'
import { Loading } from '../casino/Loading'
import { sound } from '../casino/sound'
import { AnalystPanel, AnalystPill } from './Analyst'
import { ChipPicker } from './ChipPicker'
import { Felt } from './Felt'
import { NO_BETS, colorOf, outlook, place, totalOf, undo, wagersOf, type Bets, type Spot } from './layout'
import type { RouletteView } from './types'
import { useRoulette } from './useRoulette'
import { Wheel } from './Wheel'
import { Guide } from '../guide/Guide'
import { useGuide } from '../guide/useGuide'

function statusOf(view: RouletteView, spinning: boolean, staked: number): Status {
  if (spinning) return { text: 'No more bets', tone: 'emerald' }
  if (view.result && staked > 0) return { text: 'Spin again', tone: 'gold' }
  return { text: 'Place your bets', tone: 'gold' }
}

/**
 * The roulette table. The player builds a layout of chips here, and only when
 * they spin is it sent to the server, which answers with where the ball
 * landed. Everything about the result is the server's; this draws it.
 */
export function RouletteTable({ onLeave }: { onLeave?: () => void }) {
  const { view, spinning, connection, error, send, broke, staked, retry, reading, askAbout, forgetRead } = useRoulette()
  const [showRead, setShowRead] = useState(false)
  const [bets, setBets] = useState<Bets>(NO_BETS)
  const [chip, setChip] = useState(10)
  // The stack as it stood when the wheel began to turn, shown until the ball lands.
  const [before, setBefore] = useState(0)
  // Banca's walk through a first spin: while chips go down and the wheel turns, and once the ball has landed.
  const guide = useGuide('roulette', view && { round: view.roundNumber, stage: view.result && !spinning ? 1 : 0, idle: !spinning })

  if (!view) {
    return (
      <CasinoShell>
        <Header detail="Roulette" onLeave={onLeave} />
        {connection === 'closed' || connection === 'replaced' ? (
          <Loading
            failed
            message={connection === 'replaced' ? 'This table is open somewhere else.' : 'Could not reach the table. Try again in a minute.'}
          />
        ) : (
          <Loading message="Preparing your table" />
        )}
      </CasinoShell>
    )
  }

  const result = view.result
  const shown = result && !spinning ? result : null
  const total = totalOf(bets)
  const limits = { minBet: view.minBet, maxInside: view.maxInside, maxOutside: view.maxOutside, stack: view.stack }
  // Chips left on the layout from the last spin may be more than the player now has.
  const affordable = total <= view.stack
  const { best, covered } = outlook(bets)

  // A read is of one layout. Any change to the chips on the felt puts it away.
  const change = (next: Bets) => {
    setBets(next)
    forgetRead()
  }

  const put = (spot: Spot) => {
    // A layout carried over that can no longer be afforded is cleared by the first new chip.
    const next = place(affordable ? bets : NO_BETS, spot, chip, limits)
    if (next === bets) return
    sound.chipClink()
    change(next)
  }

  const spin = () => {
    if (spinning || total === 0 || !affordable) return
    sound.click()
    setBefore(view.stack - total)
    send({ type: 'spin', bets: wagersOf(bets) })
  }

  return (
    <CasinoShell showdown={spinning}>
      <Header
        detail={view.roundNumber > 0 ? `Roulette · ${view.roundNumber}` : 'Roulette'}
        status={statusOf(view, spinning, total)}
        onLeave={onLeave}
      />

      <div className="felt mt-2.5 flex flex-1 flex-col">
        <div className="flex flex-1 flex-col items-center justify-evenly gap-2 px-2 py-3 short:py-2">
          <div className="flex w-full items-center justify-center gap-3">
            <Wheel roundNumber={view.roundNumber} pocket={result?.pocket ?? null} spinning={spinning} />

            {/* A fixed width, so the wheel does not shift as the words beside it change. */}
            <div className="flex w-36 flex-none flex-col items-start gap-2">
              <div aria-live="polite" className="min-h-12">
                {shown ? (
                  <div className="rise-in">
                    <p className="label">
                      {shown.pocket === 0 ? 'Zero' : `${shown.pocket} ${shown.color}`}
                    </p>
                    <p className="figure text-xl leading-tight font-semibold" data-tone={shown.net > 0 ? 'gain' : shown.net < 0 ? 'loss' : 'even'}>
                      {shown.net > 0 ? `+${shown.net.toLocaleString('en-US')}` : shown.net < 0 ? `−${Math.abs(shown.net).toLocaleString('en-US')}` : 'Even'}
                    </p>
                  </div>
                ) : (
                  <p className="label leading-relaxed text-gold/55!">
                    {spinning ? 'No more bets' : 'Single zero'}
                    <br />
                    {spinning ? '' : 'A number pays 35 to 1'}
                  </p>
                )}
              </div>

              <div className="glass glass--strong rounded-2xl px-3 py-1.5">
                <span className="label block">You</span>
                <span className="block text-base font-semibold text-ivory">
                  <AnimatedNumber value={spinning ? before : view.stack} />
                </span>
              </div>

              {!spinning && !broke && (
                <AnalystPill
                  reading={reading}
                  canAsk={total > 0 && affordable}
                  onAsk={() => askAbout(wagersOf(bets))}
                  onOpen={() => setShowRead(true)}
                />
              )}
            </div>
          </div>

          {/* Where the ball has been, newest first. Not a guide to where it will go. */}
          <ol className="history" aria-label="Recent results, newest first">
            {(spinning ? view.history.slice(1) : view.history).slice(0, 10).map((pocket, index) => (
              <li key={`${view.roundNumber}-${index}`} data-color={colorOf(pocket)} className="figure">
                {pocket}
              </li>
            ))}
            {view.history.length === 0 && <li className="history__empty label">No spins yet</li>}
          </ol>

          <Felt bets={affordable ? bets : NO_BETS} landed={shown?.pocket ?? null} disabled={spinning || broke !== null} onPlace={put} />
        </div>
      </div>

      <footer className="mt-2.5 flex min-h-36 flex-col justify-end">
        <ConnectionNote connection={connection} />
        {error && (
          <p role="alert" className="pb-2 text-center text-sm text-gold-bright">
            {error}
          </p>
        )}
        {!spinning && <StakedNote amount={staked} />}
        {shown?.refilled && <p className="pb-2 text-center text-sm text-gold-bright">Out of chips, so the house has staked you</p>}

        {broke ? (
          <OutOfChips broke={broke} onRetry={retry} onLeave={onLeave} />
        ) : (
          <div className="flex flex-col gap-2.5">
            <div className="flex items-center justify-between gap-2">
              <div data-guide="chips">
                <ChipPicker chip={chip} onPick={setChip} />
              </div>

              <div className="flex gap-1.5">
                <button type="button" className="btn btn--quiet px-3! text-xs" onClick={() => change(undo(bets))} disabled={spinning || total === 0}>
                  Undo
                </button>
                <button type="button" className="btn btn--quiet px-3! text-xs" onClick={() => change(NO_BETS)} disabled={spinning || total === 0}>
                  Clear
                </button>
              </div>
            </div>

            {reading.read && !spinning ? (
              <p role="status" className="coach-reason coach-reason--longer rise-in pb-0!">
                {reading.read.text}
              </p>
            ) : (
              <p className="label text-center tracking-[0.12em]!" aria-live="polite">
                {total === 0 || !affordable
                  ? 'Pick a chip, then press the layout'
                  : `Wins on ${Math.round(covered * 100)}% of the wheel · up to +${best.toLocaleString('en-US')}`}
              </p>
            )}

            <button type="button" onClick={spin} disabled={spinning || total === 0 || !affordable} data-pending={spinning} className="btn btn--raise">
              {spinning ? 'Spinning' : total > 0 && affordable ? `Spin · ${total.toLocaleString('en-US')}` : 'Place a bet'}
            </button>
          </div>
        )}
      </footer>

      {/* Put away while the wheel turns, which is the thing to watch. */}
      <Guide guide={guide} quiet={spinning} />
      {showRead && !spinning && reading.status !== 'idle' && <AnalystPanel reading={reading} onClose={() => setShowRead(false)} />}
    </CasinoShell>
  )
}
