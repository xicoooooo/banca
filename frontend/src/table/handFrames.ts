import type { TableView } from './types'

/**
 * Adds a state of the table to those kept for the hand on it, from the first
 * this player was sent. A new hand starts the list again. It is what a
 * finished hand is played back from.
 */
export function withFrame(kept: TableView[], view: TableView): TableView[] {
  if (kept.length === 0 || kept[0].handNumber !== view.handNumber) return [view]
  return kept.at(-1) === view ? kept : [...kept, view]
}
