import { useInstall, useOnline } from './install'

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
