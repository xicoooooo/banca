import { useEffect, useRef, useState } from 'react'
import { chipsFor } from '../casino/chips'
import { flyChips } from '../casino/flights'
import { sound } from '../casino/sound'
import { deriveEvents, type TableEvent } from './events'
import type { TableView } from './types'

// When things happen after a state arrives. Bets land, then are swept in, then
// the board is dealt; a showdown turns the cards before anyone is paid.
const SWEEP_AT = 260
const BOARD_AT = 520
const BOARD_STAGGER = 140
const PAYOUT_AT = 1150
const SHOWDOWN_LASTS = 2600

export type Presentation = {
  /** The last thing each seat did this street, such as "Raise to 120". */
  actions: Record<number, string>
  /** True for a moment after a showdown begins, while the room is dimmed. */
  showdown: boolean
}

/**
 * Turns changes in table state into movement and sound.
 *
 * It only ever reacts to a state that has already arrived. Nothing here delays
 * a state, changes one, or stands between the player and their controls.
 */
export function usePresentation(view: TableView | null): Presentation {
  const previous = useRef<TableView | null>(null)
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])
  const [actions, setActions] = useState<Record<number, string>>({})
  const [showdown, setShowdown] = useState(false)

  useEffect(() => {
    // The same view seen twice means nothing happened, which development
    // builds do on purpose to catch effects that are not safe to repeat.
    if (!view || previous.current === view) return

    const events = deriveEvents(previous.current, view)
    previous.current = view
    if (events.length === 0) return

    const later = (ms: number, run: () => void) => {
      timers.current.push(setTimeout(run, ms))
    }
    const chips = (amount: number) => chipsFor(amount, view.bigBlind)

    const react = (event: TableEvent) => {
      switch (event.type) {
        case 'hand_started':
          setActions({})
          setShowdown(false)
          for (let card = 0; card < 4; card++) sound.cardDeal(card * 110)
          break

        case 'blind':
          flyChips(`stack-${event.seat}`, `bet-${event.seat}`, chips(event.amount), 200)
          break

        case 'bet':
          setActions((current) => ({ ...current, [event.seat]: event.label }))
          flyChips(`stack-${event.seat}`, `bet-${event.seat}`, chips(event.amount))
          sound.chipClink()
          break

        case 'check':
          setActions((current) => ({ ...current, [event.seat]: 'Check' }))
          sound.click()
          break

        case 'fold':
          setActions((current) => ({ ...current, [event.seat]: 'Fold' }))
          sound.fold()
          break

        case 'collect':
          event.seats.forEach((seat) => flyChips(`bet-${seat}`, 'pot', chips(view.bigBlind * 4), SWEEP_AT))
          sound.chipStack(SWEEP_AT)
          break

        case 'board':
          // A new street starts with nothing said yet.
          later(BOARD_AT, () => setActions({}))
          for (let card = 0; card < event.cards; card++) {
            sound.cardDeal(BOARD_AT + card * BOARD_STAGGER)
            sound.cardFlip(BOARD_AT + card * BOARD_STAGGER + 300)
          }
          break

        case 'showdown':
          setShowdown(true)
          sound.cardFlip(250)
          sound.cardFlip(360)
          later(SHOWDOWN_LASTS, () => setShowdown(false))
          break

        case 'won':
          event.seats.forEach((seat) =>
            flyChips('pot', `stack-${seat}`, chips(event.amounts[seat] ?? 0), PAYOUT_AT),
          )
          sound.chipStack(PAYOUT_AT)
          if (event.seats.includes(view.yourSeat)) sound.win(PAYOUT_AT + 250)
          break
      }
    }

    events.forEach(react)
  }, [view])

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach(clearTimeout)
  }, [])

  return { actions, showdown }
}

/** The delays the table components use, so cards and chips keep time with the sounds. */
export const TIMING = { BOARD_AT, BOARD_STAGGER, PAYOUT_AT }
