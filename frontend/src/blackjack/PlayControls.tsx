import { useState } from 'react'
import { sound } from '../casino/sound'
import type { BlackjackAction, BlackjackView } from './types'

/** What can be done with the hand in play, or the answer to an offer of insurance. */
type PlayControlsProps = {
  view: BlackjackView
  onAct: (action: BlackjackAction) => void
  /** The play the coach has advised, if it has been asked. It is marked, never pressed for the player. */
  advised?: BlackjackAction
}

export function PlayControls({ view, onAct, advised }: PlayControlsProps) {
  // Which button was pressed, until the table answers. The controls are
  // rebuilt for each new decision, so this never needs clearing by hand.
  const [pending, setPending] = useState<BlackjackAction | null>(null)

  const act = (action: BlackjackAction) => {
    if (pending) return
    sound.click()
    setPending(action)
    onAct(action)
  }

  const button = (action: BlackjackAction, label: string, style: string) => (
    <button
      type="button"
      onClick={() => act(action)}
      disabled={pending !== null}
      data-pending={pending === action}
      data-advised={advised === action}
      className={`btn ${style} flex-1`}
    >
      {label}
      {advised === action && <span className="sr-only"> (Banca's advice)</span>}
    </button>
  )

  if (view.phase === 'insurance') {
    return (
      <div className="rise-in flex flex-col gap-3">
        <p className="label text-center">The dealer shows an ace. Insurance?</p>
        <div className="flex gap-2.5">
          {button('decline_insurance', 'No thanks', 'btn--fold')}
          {view.legal.insurance && button('insure', `Insure · ${view.insuranceCost}`, 'btn--call')}
        </div>
      </div>
    )
  }

  return (
    <div className="rise-in flex flex-col gap-2.5">
      {(view.legal.double || view.legal.split) && (
        <div className="flex gap-2.5">
          {view.legal.split && button('split', 'Split', 'btn--fold')}
          {view.legal.double && button('double', 'Double', 'btn--fold')}
        </div>
      )}
      <div className="flex gap-2.5">
        {button('stand', 'Stand', 'btn--call')}
        {button('hit', 'Hit', 'btn--raise')}
      </div>
    </div>
  )
}
