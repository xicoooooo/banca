import { useState } from 'react'
import { CHIP_CLASS, type ChipColor } from '../casino/chips'
import { sound } from '../casino/sound'

const CHIPS: { value: number; color: ChipColor }[] = [
  { value: 10, color: 'emerald' },
  { value: 25, color: 'deep' },
  { value: 50, color: 'ivory' },
  { value: 100, color: 'gold' },
]

type BetControlsProps = {
  stack: number
  minBet: number
  maxBet: number
  /** What was staked last round, offered again. */
  lastBet: number | null
  onDeal: (amount: number) => void
  /** What pressing the button does: deals, at a table alone, or only places the bet where others are betting too. */
  verb?: string
}

/** Building a bet by pressing chips, the way one is built at a table. */
export function BetControls({ stack, minBet, maxBet, lastBet, onDeal, verb = 'Deal' }: BetControlsProps) {
  const most = Math.min(maxBet, stack)
  const [amount, setAmount] = useState(() => Math.min(most, Math.max(minBet, lastBet ?? 50)))
  const [dealt, setDealt] = useState(false)

  const add = (value: number) => {
    sound.chipClink()
    setAmount((current) => Math.min(most, current + value))
  }

  const deal = () => {
    if (dealt) return
    sound.click()
    setDealt(true)
    onDeal(amount)
  }

  const ready = amount >= minBet && amount <= most

  return (
    <div className="rise-in flex flex-col gap-3 short:gap-2">
      <div className="flex items-end justify-between">
        <span className="label pb-1.5">Your bet</span>
        <span className="figure text-2xl leading-none font-semibold text-gold-bright">{amount.toLocaleString('en-US')}</span>
        <button
          type="button"
          onClick={() => setAmount(0)}
          disabled={amount === 0 || dealt}
          className="label pb-1.5 underline-offset-4 hover:text-ivory hover:underline disabled:opacity-40"
        >
          Clear
        </button>
      </div>

      <div className="flex justify-between px-1">
        {CHIPS.map((chip) => (
          <button
            key={chip.value}
            type="button"
            onClick={() => add(chip.value)}
            disabled={dealt || amount >= most}
            aria-label={`Add ${chip.value} to the bet`}
            className="chip-button"
            data-light={chip.color === 'ivory' || chip.color === 'gold'}
          >
            <span aria-hidden className={CHIP_CLASS[chip.color]} />
            <span aria-hidden>{chip.value}</span>
          </button>
        ))}
      </div>

      <button type="button" onClick={deal} disabled={!ready || dealt} data-pending={dealt} className="btn btn--raise">
        {ready ? `${verb} · ${amount.toLocaleString('en-US')}` : `Bet at least ${minBet}`}
      </button>
    </div>
  )
}
