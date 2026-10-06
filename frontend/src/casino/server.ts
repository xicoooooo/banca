// One address is configured, the poker table's, and everything else is found
// beside it. That keeps a deployment to a single setting.
const WS_BASE = (import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws/table').replace(/\/ws\/table$/, '')

export function socketUrl(path: string): string {
  return WS_BASE + path
}

/** The same server over HTTP: ws becomes http, and wss becomes https. */
export function httpUrl(path: string): string {
  return WS_BASE.replace(/^ws/, 'http') + path
}
