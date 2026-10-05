import { useState, type CSSProperties } from 'react'
import { sound } from '../casino/sound'
import type { ClientMessage, LegalView } from './types'

const chips = (amount: number) => amount.toLocaleString('en-US')

type ActionBarProps = {
  legal: LegalView
  pot: number
  committed: number
  send: (message: ClientMessage) => void
}

/** Fold, call and raise, with a sizing control when a bet or raise is possible. */
export function ActionBar({ legal, pot, committed, send }: ActionBarProps) {
  const canSize = legal.canBet || legal.canRaise
  const min = legal.canBet ? legal.minBet : legal.minRaiseTo
  const max = legal.maxTo
  const [amount, setAmount] = useState(min)

  const clamp = (value: number) => Math.min(max, Math.max(min, Math.round(value)))
  const chosen = clamp(amount)

  // A pot-sized raise first calls, then raises by the pot as it would then stand.
  const potSized = legal.canBet ? pot : committed + legal.callCost + pot + legal.callCost
  const presets = [
    { label: 'Min', value: min },
    { label: '½ Pot', value: clamp(potSized / 2) },
    { label: 'Pot', value: clamp(potSized) },
    { label: 'All-in', value: max },
  ]

  // Which button was pressed, while the table has not yet answered. The bar is
  // rebuilt for every new decision, so this never needs clearing by hand.
  const [pending, setPending] = useState<'fold' | 'call' | 'raise' | null>(null)

  const act = (button: 'fold' | 'call' | 'raise', message: ClientMessage) => {
    if (pending) return
    sound.click()
    setPending(button)
    send(message)
  }

  const fill = max > min ? ((chosen - min) / (max - min)) * 100 : 100

  return (
    <div className="rise-in flex flex-col gap-2.5">
      {canSize && (
        <div className="flex flex-col gap-1">
          <div className="flex items-end justify-between">
            <span className="label pb-1.5">{legal.canBet ? 'Bet' : 'Raise to'}</span>
            <input
              type="number"
              inputMode="numeric"
              aria-label="Amount in chips"
              min={min}
              max={max}
              value={amount}
              onChange={(event) => setAmount(Number(event.target.value))}
              onBlur={() => setAmount(chosen)}
              className="amount-field figure"
            />
            <span className="label figure pb-1.5">of {chips(max)}</span>
          </div>

          <input
            type="range"
            aria-label="Amount"
            min={min}
            max={max}
            value={chosen}
            onChange={(event) => setAmount(clamp(Number(event.target.value)))}
            className="bet-slider"
            style={{ '--fill': `${fill}%` } as CSSProperties}
          />

          <div className="grid grid-cols-4 gap-2">
            {presets.map((preset) => (
              <button
                key={preset.label}
                type="button"
                onClick={() => setAmount(preset.value)}
                // Two presets can land on the same amount; only the first is lit.
                data-selected={presets.find((other) => other.value === chosen) === preset}
                className="btn btn--quiet"
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="flex gap-2.5">
        {!legal.canCheck && (
          <button
            type="button"
            onClick={() => act('fold', { type: 'act', action: 'fold' })}
            disabled={pending !== null}
            data-pending={pending === 'fold'}
            className="btn btn--fold flex-1"
          >
            Fold
          </button>
        )}

        {legal.canCheck ? (
          <button
            type="button"
            onClick={() => act('call', { type: 'act', action: 'check' })}
            disabled={pending !== null}
            data-pending={pending === 'call'}
            className="btn btn--call flex-1"
          >
            Check
          </button>
        ) : (
          <button
            type="button"
            onClick={() => act('call', { type: 'act', action: 'call' })}
            disabled={pending !== null}
            data-pending={pending === 'call'}
            className="btn btn--call flex-1"
          >
            Call <span className="figure">{chips(legal.callCost)}</span>
          </button>
        )}

        {canSize && (
          <button
            type="button"
            onClick={() => act('raise', { type: 'act', action: legal.canBet ? 'bet' : 'raise', amount: chosen })}
            disabled={pending !== null}
            data-pending={pending === 'raise'}
            className="btn btn--raise flex-[1.2]"
          >
            {chosen === max ? 'All-in' : legal.canBet ? 'Bet' : 'Raise'} <span className="figure">{chips(chosen)}</span>
          </button>
        )}
      </div>
    </div>
  )
}
