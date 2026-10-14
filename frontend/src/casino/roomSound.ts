import { audio } from './sound'

// The sound of the room, under the game: a soft room tone, rain on the
// windows, or a slow lounge chord. All of it is made here, as the table
// sounds are, from noise and a few oscillators, so there is nothing to load
// and nothing to license. It is quiet on purpose: something to notice when it
// stops, not to listen to.

export type AmbienceKind = 'off' | 'room' | 'rain' | 'lounge'

/** How loud the whole of it is, against table sounds that peak at about 0.05. */
const LEVEL = 0.6
const FADE_S = 1.6

/** A second or more of noise to loop. Brown noise is deep and soft, white noise bright. */
function noise(ctx: BaseAudioContext, seconds: number, brown: boolean): AudioBufferSourceNode {
  const length = Math.floor(ctx.sampleRate * seconds)
  const buffer = ctx.createBuffer(1, length, ctx.sampleRate)
  const data = buffer.getChannelData(0)
  let last = 0
  for (let i = 0; i < length; i++) {
    const white = Math.random() * 2 - 1
    if (brown) {
      last = (last + 0.02 * white) / 1.02
      data[i] = last * 3.5
    } else {
      data[i] = white
    }
  }
  const source = ctx.createBufferSource()
  source.buffer = buffer
  source.loop = true
  return source
}

/** A slow swell on a gain, so a steady sound breathes rather than hums. */
function swell(ctx: BaseAudioContext, target: AudioParam, rate: number, depth: number): OscillatorNode {
  const lfo = ctx.createOscillator()
  lfo.frequency.value = rate
  const amount = ctx.createGain()
  amount.gain.value = depth
  lfo.connect(amount).connect(target)
  lfo.start()
  return lfo
}

type Playing = { kind: AmbienceKind; master: GainNode; stop: () => void }

let playing: Playing | null = null

function filter(ctx: BaseAudioContext, type: BiquadFilterType, frequency: number): BiquadFilterNode {
  const node = ctx.createBiquadFilter()
  node.type = type
  node.frequency.value = frequency
  return node
}

/** The room itself: a low, breathing hush, as of a large quiet room with people far off in it. */
function room(ctx: BaseAudioContext, out: AudioNode, volume = 0.05): () => void {
  const source = noise(ctx, 4, true)
  const low = filter(ctx, 'lowpass', 420)
  const gain = ctx.createGain()
  gain.gain.value = volume
  const lfo = swell(ctx, gain.gain, 0.06, volume * 0.35)
  source.connect(low).connect(gain).connect(out)
  source.start()
  return () => {
    source.stop()
    lfo.stop()
  }
}

/** Rain on a window: a soft wash, and now and then a drop close by. */
function rain(ctx: BaseAudioContext, out: AudioNode): () => void {
  const wash = noise(ctx, 3, false)
  const band = filter(ctx, 'bandpass', 2600)
  band.Q.value = 0.4
  const gain = ctx.createGain()
  gain.gain.value = 0.018
  const lfo = swell(ctx, gain.gain, 0.09, 0.006)
  wash.connect(band).connect(gain).connect(out)
  wash.start()

  let timer: ReturnType<typeof setTimeout>
  const drop = () => {
    const at = ctx.currentTime
    const length = 0.03 + Math.random() * 0.04
    const tick = noise(ctx, 0.1, false)
    tick.loop = false
    const pitch = filter(ctx, 'bandpass', 3000 + Math.random() * 4000)
    pitch.Q.value = 6
    const level = ctx.createGain()
    level.gain.setValueAtTime(0.0001, at)
    level.gain.exponentialRampToValueAtTime(0.02 + Math.random() * 0.03, at + 0.004)
    level.gain.exponentialRampToValueAtTime(0.0001, at + length)
    tick.connect(pitch).connect(level).connect(out)
    tick.start(at)
    tick.stop(at + length + 0.02)
    timer = setTimeout(drop, 90 + Math.random() * 520)
  }
  drop()

  return () => {
    clearTimeout(timer)
    wash.stop()
    lfo.stop()
  }
}

/** Slow chords, changing every few bars, soft enough to be furniture. Played over the room's own hush. */
const CHORDS = [
  [220, 261.63, 329.63, 392],
  [146.83, 220, 261.63, 349.23],
  [196, 246.94, 293.66, 369.99],
  [130.81, 196, 246.94, 329.63],
]

function lounge(ctx: BaseAudioContext, out: AudioNode): () => void {
  const under = room(ctx, out, 0.03)
  const warm = filter(ctx, 'lowpass', 1100)
  warm.connect(out)

  let index = 0
  let timer: ReturnType<typeof setTimeout>
  const chord = () => {
    const at = ctx.currentTime
    const lasts = 9
    for (const note of CHORDS[index % CHORDS.length]) {
      const tone = ctx.createOscillator()
      tone.type = 'triangle'
      tone.frequency.value = note
      tone.detune.value = (Math.random() - 0.5) * 8
      const level = ctx.createGain()
      level.gain.setValueAtTime(0.0001, at)
      level.gain.exponentialRampToValueAtTime(0.009, at + 2.4)
      level.gain.setValueAtTime(0.009, at + lasts - 3)
      level.gain.exponentialRampToValueAtTime(0.0001, at + lasts)
      tone.connect(level).connect(warm)
      tone.start(at)
      tone.stop(at + lasts + 0.1)
    }
    index++
    timer = setTimeout(chord, (lasts - 2.5) * 1000)
  }
  chord()

  return () => {
    clearTimeout(timer)
    under()
  }
}

/** How each sound is made, into [out], returning what stops it. Given any audio context, an offline one included. */
export const MAKERS: Record<Exclude<AmbienceKind, 'off'>, (ctx: BaseAudioContext, out: AudioNode) => () => void> = { room, rain, lounge }

/**
 * Plays [kind], fading out whatever was playing before. Nothing is played
 * while sound is off or before the person has touched the page; ask again
 * when that changes.
 */
export function playAmbience(kind: AmbienceKind) {
  if (playing?.kind === kind) return
  stopAmbience()
  if (kind === 'off') return
  const ctx = audio()
  if (!ctx) return

  const master = ctx.createGain()
  master.gain.setValueAtTime(0.0001, ctx.currentTime)
  master.gain.exponentialRampToValueAtTime(LEVEL, ctx.currentTime + FADE_S)
  master.connect(ctx.destination)
  const stop = MAKERS[kind](ctx, master)
  playing = { kind, master, stop }
}

/** Fades out whatever is playing, and lets it go. */
export function stopAmbience() {
  const was = playing
  if (!was) return
  playing = null
  const ctx = was.master.context
  was.master.gain.cancelScheduledValues(ctx.currentTime)
  was.master.gain.setValueAtTime(Math.max(was.master.gain.value, 0.0001), ctx.currentTime)
  was.master.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.6)
  setTimeout(() => {
    was.stop()
    was.master.disconnect()
  }, 700)
}
