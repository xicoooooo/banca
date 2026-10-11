import type { ChatLine } from './room'

/** How many lines of chat are kept to look back over. */
export const CHAT_KEPT = 30

/**
 * What has been said at a table: the last few lines, to look back over, and how
 * many have been heard in all. Only the count can tell that something new was
 * said, since the list stops growing once it is full.
 */
export type Talk = { lines: ChatLine[]; count: number }

export const NO_TALK: Talk = { lines: [], count: 0 }

/**
 * The table's own record of what was said, sent on sitting down. The count never
 * goes back, so sitting down again after a dropped connection leaves nothing
 * already read looking new, and the next line said still counts as one.
 */
export function heardLog(talk: Talk, lines: ChatLine[]): Talk {
  return { lines: lines.slice(-CHAT_KEPT), count: Math.max(talk.count, lines.length) }
}

/** One more line said while sitting at the table. */
export function heardLine(talk: Talk, line: ChatLine): Talk {
  return { lines: [...talk.lines, line].slice(-CHAT_KEPT), count: talk.count + 1 }
}
