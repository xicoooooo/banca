import { useState, type FormEvent } from 'react'
import { shownCode, typedCode } from '../casino/tableCode'
import { Refused, findTable } from '../player/api'

/**
 * Where a player types the code of a private table they have been told about.
 * A link does the same job in a browser, but someone with Banca on their home
 * screen has nowhere to paste one, and a code can be read out across a room.
 */
export function JoinByCode() {
  const [code, setCode] = useState('')
  const [looking, setLooking] = useState(false)
  const [refused, setRefused] = useState<string | null>(null)
  const whole = code.length === 6

  const join = async (event: FormEvent) => {
    event.preventDefault()
    if (!whole || looking) return
    setLooking(true)
    setRefused(null)
    try {
      const table = await findTable(code)
      window.location.hash = `/${table.game}/${table.id}`
    } catch (problem) {
      setRefused(problem instanceof Refused ? problem.message : 'That code could not be checked. Try again in a moment.')
      setLooking(false)
    }
  }

  return (
    <form onSubmit={join} className="join-code rise-in" style={{ ['--rise-delay' as string]: '330ms' }}>
      <label htmlFor="table-code" className="label">
        Have a table code?
      </label>
      <div className="flex gap-2">
        <input
          id="table-code"
          className="join-code__field figure"
          value={shownCode(code)}
          onChange={(event) => {
            setCode(typedCode(event.target.value))
            setRefused(null)
          }}
          placeholder="K7X 2M9"
          autoCapitalize="characters"
          autoCorrect="off"
          autoComplete="off"
          spellCheck={false}
          inputMode="text"
          aria-invalid={refused !== null}
          aria-describedby={refused ? 'table-code-problem' : undefined}
        />
        <button type="submit" className="btn btn--call px-5!" disabled={!whole || looking}>
          {looking ? 'Finding' : 'Join'}
        </button>
      </div>
      {refused && (
        <p id="table-code-problem" role="alert" className="text-sm text-gold-bright">
          {refused}
        </p>
      )}
    </form>
  )
}
