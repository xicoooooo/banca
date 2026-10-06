import { CHIP_CLASS, type ChipColor } from '../casino/chips'
import { sound } from '../casino/sound'

const CHIPS: { value: number; color: ChipColor }[] = [
  { value: 10, color: 'emerald' },
  { value: 25, color: 'deep' },
  { value: 50, color: 'ivory' },
  { value: 100, color: 'gold' },
]

/** Choosing which chip the next press on the layout will put down. */
export function ChipPicker({ chip, onPick }: { chip: number; onPick: (value: number) => void }) {
  return (
    <div className="flex gap-2">
      {CHIPS.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => {
            sound.click()
            onPick(option.value)
          }}
          aria-pressed={chip === option.value}
          aria-label={`Bet with ${option.value} chips`}
          className="chip-button chip-button--pick"
          data-light={option.color === 'ivory' || option.color === 'gold'}
        >
          <span aria-hidden className={CHIP_CLASS[option.color]} />
          <span aria-hidden>{option.value}</span>
        </button>
      ))}
    </div>
  )
}
