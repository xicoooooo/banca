// Banca's service worker. Its whole job is to make the app open: installed to
// a home screen, with a bad signal, or with none. It keeps the page and what
// the page is built from, and nothing else. Every game is played on the server,
// so nothing about a table, a player or a chip is ever answered from here.

const CACHE = 'banca-shell-v1'

// What the shell cannot be drawn without. The scripts and styles are named
// afresh by each build, so they are read out of the page itself.
const ALWAYS = ['/', '/manifest.webmanifest', '/favicon.png', '/logo-192.png', '/logo.png']

// A file is kept under its address alone. How it was asked for, as a script or
// a plain fetch, with one set of headers or another, makes it no different.
const kept = (request) => caches.match(request, { ignoreVary: true })

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE)
      const page = await fetch('/', { cache: 'no-store' })
      const html = await page.clone().text()
      const built = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((match) => match[1])

      await cache.put('/', page)
      // One missing file must not stop the rest being kept.
      await Promise.allSettled([...ALWAYS.slice(1), ...built].map((url) => cache.add(url)))
      await self.skipWaiting()
    })(),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys()
      await Promise.all(names.filter((name) => name !== CACHE).map((name) => caches.delete(name)))
      await self.clients.claim()
    })(),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)

  // The game server, sign-in and anything else that is not this site's own
  // files go straight to the network, untouched.
  if (request.method !== 'GET' || url.origin !== self.location.origin) return

  // The page: the newest there is, and the one kept only when there is no network.
  if (request.mode === 'navigate') {
    // Pages other than the app itself, such as the privacy page, are left alone.
    if (url.pathname !== '/' && url.pathname !== '/index.html') return
    event.respondWith(
      (async () => {
        try {
          const fresh = await fetch(request)
          if (fresh.ok) (await caches.open(CACHE)).put('/', fresh.clone())
          return fresh
        } catch {
          return (await kept('/')) ?? Response.error()
        }
      })(),
    )
    return
  }

  // Built files carry a fingerprint of their contents in their name, so one
  // that has been kept is never out of date.
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      (async () => {
        const held = await kept(request)
        if (held) return held
        const fresh = await fetch(request)
        if (fresh.ok) (await caches.open(CACHE)).put(request, fresh.clone())
        return fresh
      })(),
    )
    return
  }

  // Icons and the manifest: answered at once from what is kept, and refreshed behind.
  if (ALWAYS.includes(url.pathname)) {
    event.respondWith(
      (async () => {
        const held = await kept(request)
        const fresh = fetch(request)
          .then(async (response) => {
            if (response.ok) (await caches.open(CACHE)).put(request, response.clone())
            return response
          })
          .catch(() => null)
        return held ?? (await fresh) ?? Response.error()
      })(),
    )
  }
})
