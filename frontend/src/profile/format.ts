// How the dashboard's figures are written and drawn. Pure functions, so they
// can be tested without a browser.

export const GAME_NAMES: Record<string, string> = { poker: "Texas Hold'em", blackjack: 'Blackjack', roulette: 'Roulette' }

export function chips(amount: number): string {
  return Math.round(amount).toLocaleString('en-US')
}

/** A result with its sign, using a true minus so losses line up with wins. */
export function signed(amount: number): string {
  const rounded = Math.round(amount)
  if (rounded === 0) return '0'
  return `${rounded > 0 ? '+' : '−'}${chips(Math.abs(rounded))}`
}

export function percent(share: number): string {
  return `${Math.round(share * 100)}%`
}

export type Tone = 'gain' | 'loss' | 'even'

export function toneOf(amount: number): Tone {
  return amount > 0 ? 'gain' : amount < 0 ? 'loss' : 'even'
}

/** How long ago something happened, said the way a person would. */
export function ago(at: string, now: Date = new Date()): string {
  const then = new Date(at)
  const minutes = Math.floor((now.getTime() - then.getTime()) / 60_000)
  if (minutes < 1) return 'Just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} d ago`
  return shortDate(at)
}

// Written out rather than left to the browser's locale data, which does not
// agree with itself from one version to the next.
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

/** The day and month, in UTC, which is how the server counts days. */
/** How long until something, in the two largest units that matter: "3h 12m", "8m", "under a minute". */
export function until(at: string, now: Date = new Date()): string {
  const minutes = Math.ceil((new Date(at).getTime() - now.getTime()) / 60_000)
  if (minutes <= 0) return 'now'
  if (minutes === 1) return 'under a minute'
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`
}

export function shortDate(at: string): string {
  const date = new Date(at)
  return `${date.getUTCDate()} ${MONTHS[date.getUTCMonth()].slice(0, 3)}`
}

export function monthAndYear(at: string): string {
  const date = new Date(at)
  return `${MONTHS[date.getUTCMonth()]} ${date.getUTCFullYear()}`
}

/** How far through the current level the player is, from 0 to 1. */
export function levelProgress(xp: number, levelStart: number, nextLevelAt: number): number {
  if (nextLevelAt <= levelStart) return 0
  return Math.min(1, Math.max(0, (xp - levelStart) / (nextLevelAt - levelStart)))
}

export type Point = { x: number; y: number }

/**
 * Where each value sits in a chart of the given size, spread evenly from left
 * to right. The range is given a little room above and below so the line does
 * not touch the edges, and a flat series sits in the middle.
 */
export function plot(values: number[], width: number, height: number): Point[] {
  if (values.length === 0) return []
  const low = Math.min(...values)
  const high = Math.max(...values)
  const room = high === low ? 1 : (high - low) * 0.12
  const bottom = low - room
  const span = high + room - bottom
  const step = values.length > 1 ? width / (values.length - 1) : 0

  return values.map((value, index) => ({
    x: values.length > 1 ? index * step : width / 2,
    y: height - ((value - bottom) / span) * height,
  }))
}

export function linePath(points: Point[]): string {
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(' ')
}

/** The line closed down to the bottom of the chart, for the wash under it. */
export function areaPath(points: Point[], height: number): string {
  if (points.length < 2) return ''
  return `${linePath(points)} L${points[points.length - 1].x.toFixed(1)} ${height} L${points[0].x.toFixed(1)} ${height} Z`
}

/**
 * Bars rising and falling from a shared zero line, as shares of half the
 * chart's height, so the best and the worst day are drawn to the same scale.
 */
export function divergingBars(values: number[]): number[] {
  const largest = Math.max(0, ...values.map(Math.abs))
  return values.map((value) => (largest === 0 ? 0 : value / largest))
}
