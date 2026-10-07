import { useEffect, useState, useSyncExternalStore } from 'react'

/**
 * Sets the service worker going, which is what lets Banca be installed and
 * open without a network. Only in a built app: in development it would keep
 * serving yesterday's code.
 */
export function registerServiceWorker() {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', () => {
    // A browser that will not have it still runs the app, just not offline.
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}

function subscribe(changed: () => void) {
  window.addEventListener('online', changed)
  window.addEventListener('offline', changed)
  return () => {
    window.removeEventListener('online', changed)
    window.removeEventListener('offline', changed)
  }
}

/** Whether the device believes it has a network. It can be wrong about having one, never about having none. */
export function useOnline(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => navigator.onLine,
    () => true,
  )
}

/** Chrome's offer to install, held until the player asks for it. */
type InstallOffer = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }> }

const DISMISSED = 'banca.install.dismissed'

function dismissedBefore(): boolean {
  try {
    return window.localStorage.getItem(DISMISSED) === '1'
  } catch {
    return false
  }
}

/** True when Banca is already running as an installed app. */
function installed(): boolean {
  return window.matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true
}

/** An iPhone or iPad in Safari, which can install but has no way to be asked to. */
function needsShareSheet(): boolean {
  const agent = navigator.userAgent
  const apple = /iPhone|iPad|iPod/.test(agent) || (/Macintosh/.test(agent) && navigator.maxTouchPoints > 1)
  return apple && !/CriOS|FxiOS|EdgiOS/.test(agent)
}

export type Installing =
  /** The browser can install on request. */
  | { how: 'prompt'; install: () => void; dismiss: () => void }
  /** The player has to do it from the share sheet, so they are told how. */
  | { how: 'share'; dismiss: () => void }

/**
 * Whether to offer installing Banca, and how. Nothing is offered once it is
 * installed, or once the player has said no on this device.
 */
export function useInstall(): Installing | null {
  const [offer, setOffer] = useState<InstallOffer | null>(null)
  const [hidden, setHidden] = useState(() => installed() || dismissedBefore())

  useEffect(() => {
    const hold = (event: Event) => {
      // Held back, so the browser's own banner does not appear over the table.
      event.preventDefault()
      setOffer(event as InstallOffer)
    }
    const done = () => setHidden(true)
    window.addEventListener('beforeinstallprompt', hold)
    window.addEventListener('appinstalled', done)
    return () => {
      window.removeEventListener('beforeinstallprompt', hold)
      window.removeEventListener('appinstalled', done)
    }
  }, [])

  if (hidden) return null

  const dismiss = () => {
    setHidden(true)
    try {
      window.localStorage.setItem(DISMISSED, '1')
    } catch {
      // Not remembered, so it is offered again next time. No harm in that.
    }
  }

  if (offer) {
    return {
      how: 'prompt',
      dismiss,
      install: () => {
        void offer.prompt()
        void offer.userChoice.then(() => setOffer(null))
      },
    }
  }
  return needsShareSheet() ? { how: 'share', dismiss } : null
}
