import type { Rivalry } from '../player/types'
import { keptReads } from '../table/reads'
import { chips, leadOf } from './format'

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat">
      <dt className="label tracking-[0.12em]!">{label}</dt>
      <dd className="figure m-0 pt-1.5 text-xl font-semibold tracking-tight">{value}</dd>
    </div>
  )
}

const pot = (amount: number | null) => (amount === null ? '—' : chips(amount))

/**
 * The player against Banca at poker, with nobody else in the hand: the score,
 * who is ahead, the biggest pot each has taken off the other, and what has
 * come of Banca's bluffs.
 */
export function RivalryRecord({ rivalry }: { rivalry: Rivalry }) {
  const lead = leadOf(rivalry.net)
  const { bluffs } = rivalry
  // How often the player has guessed Banca's mood, which is counted on this device and not by the server.
  const reads = keptReads()

  return (
    <>
      <div className="score" role="group" aria-label={`You have won ${rivalry.won} hands and Banca ${rivalry.lost}`}>
        <div className="score__side" data-ahead={rivalry.won > rivalry.lost}>
          <span className="score__who label">You</span>
          <span className="score__hands figure">{chips(rivalry.won)}</span>
        </div>
        <span aria-hidden className="score__dash">
          –
        </span>
        <div className="score__side" data-ahead={rivalry.lost > rivalry.won}>
          <span className="score__hands figure">{chips(rivalry.lost)}</span>
          <span className="score__who label">
            <img src="/logo-192.png" alt="" width={20} height={20} />
            Banca
          </span>
        </div>
      </div>
      <p className="pb-4 text-center text-sm font-semibold" data-tone={lead.tone}>
        {lead.text}
        <span className="font-normal text-muted">
          {' '}
          over {chips(rivalry.hands)} {rivalry.hands === 1 ? 'hand' : 'hands'}
          {rivalry.even > 0 && `, ${rivalry.even} of them even`}
        </span>
      </p>

      <dl className="stats">
        <Line label="Biggest pot you took" value={pot(rivalry.biggestPotWon)} />
        <Line label="Biggest pot it took" value={pot(rivalry.biggestPotLost)} />
        <Line label="Showdowns won" value={rivalry.showdowns === 0 ? '—' : `${rivalry.showdownsWon} of ${rivalry.showdowns}`} />
        <Line label="Best run" value={rivalry.bestRun === 0 ? '—' : `${rivalry.bestRun} in a row`} />
        {bluffs && (
          <>
            <Line label="Bluffs you caught" value={String(bluffs.caught)} />
            <Line label="Bluffs you folded to" value={String(bluffs.worked)} />
            <Line label="Biggest bluff it got away with" value={pot(bluffs.biggest)} />
          </>
        )}
        {reads.total > 0 && (
          <>
            <Line label="Moods read right" value={`${reads.right} of ${reads.total}`} />
            <Line label="Best run of reads" value={reads.best === 0 ? '—' : `${reads.best} in a row`} />
          </>
        )}
      </dl>
      <p className="pt-4 text-sm leading-relaxed text-muted">
        {bluffs
          ? `Banca has bluffed in ${bluffs.tried} of the last ${chips(bluffs.hands)} ${bluffs.hands === 1 ? 'hand' : 'hands'} between you. A bluff is caught when you call it down and win.`
          : 'Banca’s bluffs are counted from your next hand against it.'}{' '}
        Only hands with nobody else in them count here.
      </p>
    </>
  )
}
