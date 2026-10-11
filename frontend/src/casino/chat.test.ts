import { describe, expect, it } from 'vitest'
import { CHAT_KEPT, NO_TALK, heardLine, heardLog } from './chat'
import type { ChatLine } from './room'

const line = (text: string): ChatLine => ({ from: 'Ana', text, emote: false })

function say(count: number, from = NO_TALK) {
  let talk = from
  for (let index = 0; index < count; index++) talk = heardLine(talk, line(`line ${index}`))
  return talk
}

describe('what has been said at a table', () => {
  it('starts with the lines the table already had', () => {
    const talk = heardLog(NO_TALK, [line('a'), line('b')])
    expect(talk.lines.map((said) => said.text)).toEqual(['a', 'b'])
    expect(talk.count).toBe(2)
  })

  it('keeps only the last lines to look back over', () => {
    const talk = say(CHAT_KEPT + 5)
    expect(talk.lines).toHaveLength(CHAT_KEPT)
    expect(talk.lines.at(-1)?.text).toBe(`line ${CHAT_KEPT + 4}`)
  })

  it('goes on counting once the kept lines are full', () => {
    const full = say(CHAT_KEPT)
    const next = heardLine(full, line('one more'))
    expect(next.lines).toHaveLength(CHAT_KEPT)
    expect(next.count).toBe(full.count + 1)
    expect(say(CHAT_KEPT * 3).count).toBe(CHAT_KEPT * 3)
  })

  it('does not count back when the table sends its record again', () => {
    const before = say(CHAT_KEPT + 12)
    const after = heardLog(before, before.lines)
    expect(after.count).toBe(before.count)
    expect(heardLine(after, line('new')).count).toBe(before.count + 1)
  })
})
