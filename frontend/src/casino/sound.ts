// Table sounds, made in the browser rather than loaded from files: nothing to
// download, nothing to license, and nothing for the game to wait on. Every
// sound is short and quiet, and the game is complete without any of them.

const STORAGE_KEY = 'banca:muted'

let context: AudioContext | null = null
let unlocked = false
let muted = readMuted()

function readMuted(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

// Browsers only allow sound after the person has touched the page, so the
// audio context is not created until they have.
if (typeof window !== 'undefined') {
  const unlock = () => {
    unlocked = true
    window.removeEventListener('pointerdown', unlock)
    window.removeEventListener('keydown', unlock)
  }
  window.addEventListener('pointerdown', unlock)
  window.addEventListener('keydown', unlock)
}

function audio(): AudioContext | null {
  if (muted || !unlocked) return null
  try {
    context ??= new AudioContext()
    if (context.state === 'suspended') void context.resume()
    return context
  } catch {
    return null
  }
}

/** A burst of filtered noise: the paper and felt sounds. */
function hiss(ctx: AudioContext, at: number, length: number, frequency: number, volume: number) {
  const samples = Math.floor(ctx.sampleRate * length)
  const buffer = ctx.createBuffer(1, samples, ctx.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < samples; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / samples)

  const source = ctx.createBufferSource()
  source.buffer = buffer
  const filter = ctx.createBiquadFilter()
  filter.type = 'bandpass'
  filter.frequency.value = frequency
  filter.Q.value = 0.9
  const gain = ctx.createGain()
  gain.gain.setValueAtTime(volume, at)
  gain.gain.exponentialRampToValueAtTime(0.0001, at + length)

  source.connect(filter).connect(gain).connect(ctx.destination)
  source.start(at)
}

/** A short decaying tone: the ceramic and bell sounds. */
function ping(ctx: AudioContext, at: number, frequency: number, length: number, volume: number) {
  const oscillator = ctx.createOscillator()
  oscillator.type = 'triangle'
  oscillator.frequency.setValueAtTime(frequency, at)
  const gain = ctx.createGain()
  gain.gain.setValueAtTime(0.0001, at)
  gain.gain.exponentialRampToValueAtTime(volume, at + 0.004)
  gain.gain.exponentialRampToValueAtTime(0.0001, at + length)

  oscillator.connect(gain).connect(ctx.destination)
  oscillator.start(at)
  oscillator.stop(at + length + 0.02)
}

function play(make: (ctx: AudioContext, now: number) => void, delayMs = 0) {
  const ctx = audio()
  if (ctx) make(ctx, ctx.currentTime + delayMs / 1000)
}

export const sound = {
  cardDeal: (delayMs = 0) => play((ctx, t) => hiss(ctx, t, 0.09, 2600, 0.07), delayMs),
  cardFlip: (delayMs = 0) => play((ctx, t) => hiss(ctx, t, 0.07, 4200, 0.05), delayMs),
  chipClink: (delayMs = 0) =>
    play((ctx, t) => {
      ping(ctx, t, 3100, 0.06, 0.04)
      ping(ctx, t + 0.035, 3900, 0.07, 0.035)
    }, delayMs),
  chipStack: (delayMs = 0) =>
    play((ctx, t) => {
      for (let i = 0; i < 4; i++) ping(ctx, t + i * 0.045, 2700 + i * 260, 0.06, 0.03)
    }, delayMs),
  click: () => play((ctx, t) => ping(ctx, t, 1500, 0.03, 0.02)),
  fold: (delayMs = 0) => play((ctx, t) => hiss(ctx, t, 0.16, 1100, 0.05), delayMs),
  win: (delayMs = 0) =>
    play((ctx, t) => {
      ping(ctx, t, 784, 0.35, 0.035)
      ping(ctx, t + 0.11, 988, 0.35, 0.035)
      ping(ctx, t + 0.22, 1175, 0.5, 0.04)
    }, delayMs),
}

export function isMuted(): boolean {
  return muted
}

export function setMuted(next: boolean): void {
  muted = next
  try {
    localStorage.setItem(STORAGE_KEY, next ? '1' : '0')
  } catch {
    // Private browsing may refuse storage; the choice then lasts for the visit.
  }
}
