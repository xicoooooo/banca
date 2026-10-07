import { useInstall, useOnline } from './install'
import { useSessionReminder } from './session'

/**
 * Said across the top of every screen while the device has no network. The
 * app still opens, because its shell is kept on the device, but every game is
 * played on the server, so there is nothing to play until the network is back.
 */
export function OfflineNote() {
  const online = useOnline()
  if (online) return null
  return (
    <p role="status" className="offline-note">
      You are offline. Banca will pick up where you left off when you are back.
    </p>
  )
}

/** An offer to put Banca on the home screen, in the lobby, for a player who has not already. */
export function InstallCard() {
  const installing = useInstall()
  if (!installing) return null

  return (
    <div className="install-card rise-in">
      <img src="/logo-192.png" alt="" width="40" height="40" className="flex-none rounded-xl" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold tracking-tight text-ivory">Put Banca on your home screen</p>
        <p className="pt-0.5 text-xs leading-relaxed text-muted">
          {installing.how === 'prompt'
            ? 'It opens full screen, like an app. Nothing to download from a store.'
            : "Press your browser's Share button, then Add to Home Screen. It opens full screen, like an app."}
        </p>
        <div className="flex gap-2 pt-2.5">
          {installing.how === 'prompt' && (
            <button type="button" className="btn btn--call px-4! py-2! text-sm" onClick={installing.install}>
              Install
            </button>
          )}
          <button type="button" className="btn btn--quiet px-4! py-2! text-sm" onClick={installing.dismiss}>
            {installing.how === 'prompt' ? 'Not now' : 'Got it'}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * A word when the player has been at it a while. The chips are play money but
 * the time is real, so Banca says how long it has been and leaves the choice
 * with them. It covers nothing they need and stops nothing they are doing.
 */
export function SessionReminder() {
  const reminder = useSessionReminder()
  if (!reminder) return null

  return (
    <aside className="guide" aria-label="A reminder of how long you have been playing">
      <div className="guide__card rise-in" role="status">
        <p className="label text-gold!">A moment</p>
        <p className="pt-2 text-sm leading-relaxed text-ivory/90">
          You have been playing for {reminder.playedFor}. The chips are play money, but the time is yours. A good moment for a break?
        </p>
        <div className="flex items-center justify-between gap-3 pt-2.5">
          <a
            href="#/"
            className="guide__skip label"
            onClick={reminder.keepPlaying}
          >
            Leave the table
          </a>
          <button type="button" className="btn btn--call px-5! py-2! text-sm" onClick={reminder.keepPlaying}>
            Keep playing
          </button>
        </div>
        <p className="pt-2 text-xs leading-relaxed text-muted">You can change or turn off this reminder on your profile.</p>
      </div>
    </aside>
  )
}
