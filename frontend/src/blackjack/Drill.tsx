import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Card } from '../casino/Card'
import { CasinoShell } from '../casino/CasinoShell'
import { Header } from '../casino/Header'
import { Loading } from '../casino/Loading'
import { httpUrl } from '../casino/server'
import { sound } from '../casino/sound'
import { ACTION_NAMES, bestDrill, handName, keepIfBest, markOf, scoreOf, type Answer, type Drill, type DrillAction, type DrillQuestion, type Mark } from './drilling'

const MARKS: Record<Mark, string> = { right: 'Right', close: 'Close', wrong: 'Not the best play', slow: 'Too slow' }

/** How long a right answer is shown before the next hand comes. A wrong one waits to be read. */
const RIGHT_FOR_MS = 900

type Stage = { at: 'loading' } | { at: 'failed' } | { at: 'ready' } | { at: 'asking'; index: number } | { at: 'told'; index: number } | { at: 'done' }

/**
 * A drill: ten blackjack hands, each to be decided in a few seconds, marked
 * at once against the best play. No chips are staked and none are won. It is
 * the table's hard decisions without the table, to be taken as often as liked.
 */
export function BlackjackDrill({ onLeave }: { onLeave: () => void }) {
  const [drill, setDrill] = useState<Drill | null>(null)
  const [stage, setStage] = useState<Stage>({ at: 'loading' })
  const [answers, setAnswers] = useState<Answer[]>([])
  const [round, setRound] = useState(0)
  const [newBest, setNewBest] = useState(false)
  const askedAt = useRef(0)

  // A fresh drill is dealt when the screen opens and each time another is asked for.
  useEffect(() => {
    let gone = false
    fetch(httpUrl('/blackjack/drill'))
      .then((response) => (response.ok ? (response.json() as Promise<Drill>) : Promise.reject(new Error(String(response.status)))))
      .then(
        (dealt) => {
          if (gone) return
          setDrill(dealt)
          setAnswers([])
          setStage({ at: 'ready' })
        },
        () => {
          if (!gone) setStage({ at: 'failed' })
        },
      )
    return () => {
      gone = true
    }
  }, [round])

  const ask = (index: number) => {
    askedAt.current = Date.now()
    setStage({ at: 'asking', index })
  }

  const answer = useCallback(
    (played: DrillAction | null) => {
      if (!drill || stage.at !== 'asking') return
      const question = drill.questions[stage.index]
      const given: Answer = { played, ms: Date.now() - askedAt.current }
      const mark = markOf(question, given)
      if (mark === 'right') sound.chipClink()
      else sound.fold()
      setAnswers((before) => [...before, given])
      setStage({ at: 'told', index: stage.index })
    },
    [drill, stage],
  )

  const next = useCallback(() => {
    if (!drill || stage.at !== 'told') return
    if (stage.index + 1 < drill.questions.length) return ask(stage.index + 1)
    // A first drill is only a first drill: it is a best yet once there was one to beat.
    const before = bestDrill()
    setNewBest(keepIfBest(scoreOf(drill, answers).right) && before !== null)
    setStage({ at: 'done' })
  }, [drill, stage, answers])

  // The clock: a hand not decided in time is marked as too slow.
  useEffect(() => {
    if (!drill || stage.at !== 'asking') return
    const timer = setTimeout(() => answer(null), drill.secondsEach * 1000)
    return () => clearTimeout(timer)
  }, [drill, stage, answer])

  // A right answer moves on by itself. Anything else waits, so that why can be read.
  const told = stage.at === 'told' && drill ? markOf(drill.questions[stage.index], answers[stage.index]) : null
  useEffect(() => {
    if (told !== 'right') return
    const timer = setTimeout(next, RIGHT_FOR_MS)
    return () => clearTimeout(timer)
  }, [told, next])

  const again = () => {
    sound.click()
    setStage({ at: 'loading' })
    setRound((count) => count + 1)
  }

  if (stage.at === 'loading' || stage.at === 'failed' || !drill) {
    return (
      <CasinoShell>
        <Header detail="Blackjack drill" onLeave={onLeave} />
        <Loading failed={stage.at === 'failed'} message={stage.at === 'failed' ? 'Could not deal a drill. Try again in a minute.' : 'Dealing a drill'} />
      </CasinoShell>
    )
  }

  if (stage.at === 'ready') {
    const best = bestDrill()
    return (
      <CasinoShell>
        <Header detail="Blackjack drill" onLeave={onLeave} />
        <div className="m-auto flex w-full max-w-md flex-col gap-4 py-6">
          <section className="panel rise-in text-center">
            <p className="label text-gold!">Practice</p>
            <h1 className="pt-2 text-3xl font-semibold tracking-tight text-ivory">
              {drill.questions.length} hands, {drill.secondsEach} seconds each
            </h1>
            <p className="pt-3 text-sm leading-relaxed text-ivory/80">
              You are shown your two cards and the dealer's one. Choose how to play it. Each choice is marked at once against the best play, and a miss
              tells you why.
            </p>
            <p className="pt-2 text-sm leading-relaxed text-muted">No chips are staked and none are won.</p>
            {best !== null && <p className="label pt-4">Your best · {best} of {drill.questions.length}</p>}
            <button
              type="button"
              className="btn btn--raise mt-5 w-full"
              onClick={() => {
                sound.click()
                ask(0)
              }}
            >
              Start
            </button>
          </section>
        </div>
      </CasinoShell>
    )
  }

  if (stage.at === 'done') return <DrillSummary drill={drill} answers={answers} newBest={newBest} onAgain={again} onLeave={onLeave} />

  const question = drill.questions[stage.index]
  const given = stage.at === 'told' ? answers[stage.index] : null
  const mark = given ? markOf(question, given) : null

  return (
    <CasinoShell>
      <Header detail={`Blackjack drill · ${stage.index + 1} of ${drill.questions.length}`} status={{ text: mark ? MARKS[mark] : 'Your play', tone: mark === 'right' ? 'emerald' : 'gold' }} onLeave={onLeave} />

      <div className="felt bj-felt mt-2.5 flex flex-1 flex-col">
        <div className="felt__surface flex flex-1 flex-col items-center justify-evenly gap-3 px-2 py-4">
          <DrillHand label="Dealer shows" cards={[question.dealer, null]} size="var(--card-board)" seed={stage.index * 10} />
          <DrillHand label={handName(question)} cards={question.cards} size="var(--card-hero)" seed={stage.index * 10 + 4} mine />
        </div>
      </div>

      <footer className="mt-2.5 flex min-h-44 flex-col justify-end">
        {stage.at === 'asking' ? (
          <>
            <div className="wait-line" aria-hidden data-mine="true">
              <span key={stage.index} style={{ animationDuration: `${drill.secondsEach * 1000}ms` }} />
            </div>
            <div className="drill-plays" role="group" aria-label="How to play this hand">
              {question.plays.map((play) => (
                <button key={play.action} type="button" className={`btn ${play.action === 'stand' ? 'btn--fold' : play.action === 'hit' ? 'btn--call' : 'btn--raise'}`} onClick={() => answer(play.action)}>
                  {ACTION_NAMES[play.action]}
                </button>
              ))}
            </div>
          </>
        ) : (
          <div className="rise-in flex flex-col gap-3" role="status">
            <div className="drill-told" data-mark={mark}>
              <p className="drill-told__mark">{mark === 'right' ? `Right: ${ACTION_NAMES[question.best].toLowerCase()}` : `${MARKS[mark!]} · the play is to ${ACTION_NAMES[question.best].toLowerCase()}`}</p>
              {mark !== 'right' && <p className="drill-told__why">{question.reason}</p>}
            </div>
            {mark !== 'right' && (
              <button
                type="button"
                className="btn btn--raise w-full"
                // Focused as it appears, so that Enter or Space moves a keyboard player on.
                autoFocus
                onClick={() => {
                  sound.click()
                  next()
                }}
              >
                {stage.index + 1 < drill.questions.length ? 'Next hand' : 'See how you did'}
              </button>
            )}
          </div>
        )}
      </footer>
    </CasinoShell>
  )
}

function DrillHand({ label, cards, size, seed, mine = false }: { label: string; cards: (string | null)[]; size: string; seed: number; mine?: boolean }) {
  return (
    <div className="flex flex-col items-center gap-2">
      <p className={`label ${mine ? 'text-gold-bright!' : ''}`}>{label}</p>
      <div className="flex justify-center gap-2" style={{ '--w': size } as CSSProperties}>
        {cards.map((card, index) => (
          <Card key={`${seed}-${index}`} card={card} dealDelay={index * 90} dealFrom={{ x: '0px', y: mine ? '-12vh' : '-8vh' }} flipDelay={220 + index * 90} seed={seed + index} />
        ))}
      </div>
    </div>
  )
}

function DrillSummary({ drill, answers, newBest, onAgain, onLeave }: { drill: Drill; answers: Answer[]; newBest: boolean; onAgain: () => void; onLeave: () => void }) {
  const score = scoreOf(drill, answers)
  const missed = drill.questions
    .map((question, index) => ({ question, answer: answers[index], mark: markOf(question, answers[index]) }))
    .filter((entry) => entry.mark !== 'right')

  return (
    <CasinoShell>
      <Header detail="Blackjack drill" onLeave={onLeave} />
      <div className="flex flex-col gap-4 py-4 sm:gap-5 sm:py-6">
        <section className="panel rise-in text-center">
          <p className="label text-gold!">{newBest ? 'Your best yet' : score.grade}</p>
          <p className="figure pt-2 text-5xl font-bold tracking-tight text-gold-bright">
            {score.right}
            <span className="text-2xl font-semibold text-muted"> of {score.of}</span>
          </p>
          <p className="pt-3 text-sm leading-relaxed text-ivory/80">
            {score.right === score.of
              ? 'Every hand played the way the arithmetic would.'
              : `On average your plays gave up ${Math.round(score.cost / score.of)} chips for every 100 staked.`}
            {score.seconds !== null && ` You took ${score.seconds} seconds a hand.`}
          </p>
          <div className="flex gap-2.5 pt-5">
            <button type="button" className="btn btn--fold flex-1" onClick={onLeave}>
              Back
            </button>
            <button type="button" className="btn btn--raise flex-1" onClick={onAgain}>
              Another drill
            </button>
          </div>
        </section>

        {missed.length > 0 && (
          <section className="panel rise-in" style={{ ['--rise-delay' as string]: '80ms' }}>
            <h2 className="label pb-3 text-gold!">The ones to look at again</h2>
            <ul className="drill-missed">
              {missed.map(({ question, answer, mark }, index) => (
                <li key={index}>
                  <p className="font-semibold text-ivory">
                    {handName(question)} against {spoken(question)}
                  </p>
                  <p className="text-sm text-muted">
                    {answer.played ? `You chose to ${ACTION_NAMES[answer.played].toLowerCase()}` : 'You ran out of time'}
                    {mark === 'close' ? ', which is close' : ''}. The play is to <span className="text-gold-bright">{ACTION_NAMES[question.best].toLowerCase()}</span>.
                  </p>
                  <p className="pt-1 text-sm leading-relaxed text-ivory/75">{question.reason}</p>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </CasinoShell>
  )
}

/** The dealer's card in words: "an ace", "a ten", "a 6". */
function spoken(question: DrillQuestion): string {
  const rank = question.dealer[0]
  if (rank === 'A') return 'an ace'
  if ('TJQK'.includes(rank)) return 'a ten'
  return rank === '8' ? 'an 8' : `a ${rank}`
}
