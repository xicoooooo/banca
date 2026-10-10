import { useEffect } from 'react'
import { sound } from '../casino/sound'
import type { Dashboard } from '../player/types'
import { allowed, asksFor, isUnlocked, itemsOf, type Kind } from './catalogue'
import { progressOf, useTableStyle } from './tableStyle'

const KINDS: { kind: Kind; title: string }[] = [
  { kind: 'cards', title: 'Card backs' },
  { kind: 'felt', title: 'Felts' },
]

/**
 * The card backs and felts a player has opened, and the ones still to come
 * with what each asks for. Choosing one changes the tables at once.
 */
export function TableStylePicker({ dashboard }: { dashboard: Dashboard }) {
  const [style, setStyle] = useTableStyle()
  const progress = progressOf(dashboard)

  // A choice this player has not opened, left behind by someone else who played here, is put back to the plain one.
  const cards = allowed('cards', style.cards, progress)
  const felt = allowed('felt', style.felt, progress)
  useEffect(() => {
    if (cards !== style.cards || felt !== style.felt) setStyle({ cards, felt })
  }, [cards, felt, style.cards, style.felt, setStyle])

  const chosen = { cards, felt }

  return (
    <div className="flex flex-col gap-5">
      {KINDS.map(({ kind, title }) => {
        const items = itemsOf(kind)
        const open = items.filter((item) => isUnlocked(item, progress)).length
        return (
          <div key={kind}>
            <div className="flex items-baseline justify-between gap-3 pb-2.5">
              <h3 className="text-sm font-semibold text-ivory">{title}</h3>
              <span className="label figure tracking-[0.12em]!">
                {open} of {items.length}
              </span>
            </div>
            <div className="swatches" role="group" aria-label={title}>
              {items.map((item) => {
                const unlocked = isUnlocked(item, progress)
                const selected = chosen[kind] === item.id
                return (
                  <button
                    key={item.id}
                    type="button"
                    className="swatch"
                    data-selected={selected}
                    data-locked={!unlocked}
                    aria-pressed={selected}
                    disabled={!unlocked}
                    aria-label={unlocked ? item.name : `${item.name}, locked. ${asksFor(item.unlock)}`}
                    onClick={() => {
                      sound.click()
                      setStyle({ ...chosen, [kind]: item.id })
                    }}
                  >
                    <span aria-hidden className={kind === 'cards' ? 'swatch__card card-back' : 'swatch__felt'} data-style={item.id}>
                      {kind === 'cards' && 'B'}
                    </span>
                    <span className="swatch__name">{item.name}</span>
                    <span className="swatch__ask">{unlocked ? (selected ? 'In use' : 'Unlocked') : asksFor(item.unlock)}</span>
                  </button>
                )
              })}
            </div>
          </div>
        )
      })}
      {!dashboard.player.signedIn && (
        <p className="text-sm leading-relaxed text-muted">Some of these are won in the weekly leagues, which are for players who have signed in.</p>
      )}
    </div>
  )
}
