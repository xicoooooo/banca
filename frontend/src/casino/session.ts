import { useEffect, useState } from 'react'

/** The lengths of play after which a player can ask to be reminded, in minutes. Nought is never. */
export const REMINDER_CHOICES = [0, 30, 60, 120] as const

/** Reminded after an hour unless the player chooses otherwise. */
const DEFAULT_MINUTES = 60

/** A gap this long with Banca closed or out of sight ends a sitting, and the next visit starts a new one. */
export const AWAY_ENDS_SITTING_MS = 10 * 60_000

const SETTING = 'banca.session.remind'
const SITTING = 'banca.session.sitting'

/**
 * One sitting: when it began, when Banca was last seen open, and when the
 * player was last reminded how long they had been at it.
 */
export type Sitting = { startedAt: number; lastSeen: number; remindedAt: number | null }

/**
 * Brings a sitting up to [now]. One that has been away too long, or was never
 * begun, starts afresh; otherwise it carries on and is marked as seen.
 */
export function sittingAt(before: Sitting | null, now: number): Sitting {
  if (!before || now - before.lastSeen > AWAY_ENDS_SITTING_MS || now < before.startedAt) {
    return { startedAt: now, lastSeen: now, remindedAt: null }
  }
  return { ...before, lastSeen: now }
}

/** Whether a reminder is owed: the chosen length has passed since the sitting began, or since the last reminder. */
export function reminderDue(sitting: Sitting, now: number, minutes: number): boolean {
  if (minutes <= 0) return false
  return now - (sitting.remindedAt ?? sitting.startedAt) >= minutes * 60_000
}

/** How long a sitting has lasted, in words: "30 minutes", "1 hour", "2 hours 30 minutes". */
export function lengthInWords(ms: number): string {
  const total = Math.max(1, Math.round(ms / 60_000))
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  const parts = [hours > 0 && `${hours} hour${hours === 1 ? '' : 's'}`, minutes > 0 && `${minutes} minute${minutes === 1 ? '' : 's'}`]
  return parts.filter(Boolean).join(' ')
}

// The choice is kept on this device, and the sitting only for as long as the
// tab lives. Where storage is not to be had, the reminder keeps its default
// and a sitting lasts until the page is closed.
export function reminderMinutes(): number {
  try {
    const kept = window.localStorage.getItem(SETTING)
    const minutes = kept === null ? DEFAULT_MINUTES : Number(kept)
    return (REMINDER_CHOICES as readonly number[]).includes(minutes) ? minutes : DEFAULT_MINUTES
  } catch {
    return DEFAULT_MINUTES
  }
}

export function setReminderMinutes(minutes: number) {
  try {
    window.localStorage.setItem(SETTING, String(minutes))
  } catch {
    // Not remembered. The default stands.
  }
  window.dispatchEvent(new Event(CHANGED))
}

const CHANGED = 'banca:session-reminder'

let unkept: Sitting | null = null

function readSitting(): Sitting | null {
  try {
    const kept = window.sessionStorage.getItem(SITTING)
    return kept ? (JSON.parse(kept) as Sitting) : unkept
  } catch {
    return unkept
  }
}

function keepSitting(sitting: Sitting) {
  unkept = sitting
  try {
    window.sessionStorage.setItem(SITTING, JSON.stringify(sitting))
  } catch {
    // Kept in memory instead, above.
  }
}

/** How often the sitting is looked at. A reminder a few seconds late is still a reminder. */
const LOOK_EVERY_MS = 20_000

export type Reminder = { playedFor: string; keepPlaying: () => void }

/**
 * Watches how long the player has been at Banca in one sitting, and says when
 * a reminder is owed. Time with Banca out of sight is not counted as being
 * seen, so leaving it in a background tab overnight is not a nine-hour sitting.
 */
export function useSessionReminder(): Reminder | null {
  const [due, setDue] = useState<number | null>(null)

  useEffect(() => {
    const look = () => {
      if (document.hidden) return
      const now = Date.now()
      const sitting = sittingAt(readSitting(), now)
      keepSitting(sitting)
      setDue(reminderDue(sitting, now, reminderMinutes()) ? now - sitting.startedAt : null)
    }

    look()
    const timer = setInterval(look, LOOK_EVERY_MS)
    document.addEventListener('visibilitychange', look)
    window.addEventListener(CHANGED, look)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', look)
      window.removeEventListener(CHANGED, look)
    }
  }, [])

  if (due === null) return null
  return {
    playedFor: lengthInWords(due),
    keepPlaying: () => {
      const now = Date.now()
      keepSitting({ ...sittingAt(readSitting(), now), remindedAt: now })
      setDue(null)
    },
  }
}

/** The player's chosen reminder, kept in step with wherever it is changed. */
export function useReminderSetting(): [number, (minutes: number) => void] {
  const [minutes, setMinutes] = useState(reminderMinutes)
  useEffect(() => {
    const changed = () => setMinutes(reminderMinutes())
    window.addEventListener(CHANGED, changed)
    return () => window.removeEventListener(CHANGED, changed)
  }, [])
  return [minutes, setReminderMinutes]
}
