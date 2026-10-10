import { seatLabel, type CardSeat, type HandCard } from './handCard'

// Draws a hand card as a picture. It is painted by hand on a canvas, with
// nothing fetched but Banca's own mark, so the picture is made on the player's
// device and no server ever sees it.

export const CARD_WIDTH = 1080
export const CARD_HEIGHT = 1350

const SANS = "-apple-system, 'SF Pro Display', Inter, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif"
const SERIF = "'Iowan Old Style', 'Palatino Linotype', Palatino, Georgia, serif"

const IVORY = '#f5f1e8'
const GOLD = '#d9a441'
const GOLD_BRIGHT = '#f1c75b'
const MUTED = 'rgb(245 241 232 / 0.62)'

const SUITS: Record<string, { symbol: string; red: boolean }> = {
  s: { symbol: '♠', red: false },
  h: { symbol: '♥', red: true },
  d: { symbol: '♦', red: true },
  c: { symbol: '♣', red: false },
}

type Pen = CanvasRenderingContext2D

/** The room's colours, as the player's chosen felt has them, so the picture matches their table. */
function roomColours(): string[] {
  const style = getComputedStyle(document.documentElement)
  const fallback = ['#0b604b', '#073d31', '#052e26', '#031e19']
  return fallback.map((colour, index) => style.getPropertyValue(`--room-${index + 1}`).trim() || colour)
}

function rounded(pen: Pen, x: number, y: number, width: number, height: number, radius: number) {
  pen.beginPath()
  pen.roundRect(x, y, width, height, radius)
}

/** Capitals set wide apart, as the labels at the tables are. */
function label(pen: Pen, text: string, x: number, y: number, size: number, colour: string) {
  pen.font = `600 ${size}px ${SANS}`
  pen.fillStyle = colour
  pen.textAlign = 'left'
  pen.textBaseline = 'alphabetic'
  const gap = size * 0.2
  const letters = [...text.toUpperCase()]
  const width = letters.reduce((sum, letter) => sum + pen.measureText(letter).width + gap, -gap)
  let at = x - width / 2
  for (const letter of letters) {
    pen.fillText(letter, at, y)
    at += pen.measureText(letter).width + gap
  }
}

/** Breaks [text] into lines no wider than [width], in the font the pen is holding. */
function wrapped(pen: Pen, text: string, width: number): string[] {
  const lines: string[] = []
  let line = ''
  for (const word of text.split(' ')) {
    const longer = line ? `${line} ${word}` : word
    if (line && pen.measureText(longer).width > width) {
      lines.push(line)
      line = word
    } else {
      line = longer
    }
  }
  return line ? [...lines, line] : lines
}

function face(pen: Pen, card: string, x: number, y: number, width: number, lit: boolean) {
  const height = width * 1.4
  const suit = SUITS[card[1]] ?? SUITS.s
  const rank = card[0] === 'T' ? '10' : card[0]
  const ink = suit.red ? '#b3261e' : '#16181d'

  pen.save()
  pen.shadowColor = lit ? 'rgb(241 199 91 / 0.55)' : 'rgb(0 0 0 / 0.5)'
  pen.shadowBlur = lit ? width * 0.3 : width * 0.16
  pen.shadowOffsetY = lit ? 0 : width * 0.06
  rounded(pen, x, y, width, height, width * 0.09)
  pen.fillStyle = '#fbf8f0'
  pen.fill()
  pen.restore()

  pen.fillStyle = ink
  pen.textAlign = 'center'
  pen.textBaseline = 'middle'
  const corner = (cx: number, cy: number) => {
    pen.font = `700 ${width * 0.27}px ${SANS}`
    pen.fillText(rank, cx, cy)
    pen.font = `${width * 0.2}px ${SANS}`
    pen.fillText(suit.symbol, cx, cy + width * 0.25)
  }
  corner(x + width * 0.19, y + width * 0.22)
  pen.save()
  pen.translate(x + width * 0.81, y + height - width * 0.22)
  pen.rotate(Math.PI)
  corner(0, 0)
  pen.restore()
  pen.font = `${width * 0.5}px ${SANS}`
  pen.fillText(suit.symbol, x + width / 2, y + height / 2)
}

/** A place on the board no card came to. */
function slot(pen: Pen, x: number, y: number, width: number) {
  rounded(pen, x, y, width, width * 1.4, width * 0.09)
  pen.fillStyle = 'rgb(0 0 0 / 0.16)'
  pen.fill()
  pen.strokeStyle = 'rgb(255 255 255 / 0.07)'
  pen.lineWidth = 2
  pen.stroke()
}

/** A row of cards, centred on [centre]. */
function row(pen: Pen, cards: (string | null)[], centre: number, y: number, width: number, lit: boolean) {
  const gap = width * 0.12
  let x = centre - (cards.length * width + (cards.length - 1) * gap) / 2
  for (const card of cards) {
    if (card) face(pen, card, x, y, width, lit)
    else slot(pen, x, y, width)
    x += width + gap
  }
}

/**
 * One player's cards under their label. A seat with neighbours is [crowded]:
 * there the name goes above the cards and the hand below, cut short if need
 * be, so that neither runs into the seat beside it.
 */
function seat(pen: Pen, who: CardSeat, centre: number, y: number, width: number, crowded = false) {
  const colour = who.won ? GOLD_BRIGHT : MUTED
  if (!crowded) {
    label(pen, seatLabel(who), centre, y, 24, colour)
  } else {
    label(pen, who.name.length > 12 ? `${who.name.slice(0, 11)}…` : who.name, centre, y, 20, colour)
    if (who.hand) label(pen, seatLabel({ ...who, name: '' }).replace(/^ · /, ''), centre, y + 26 + width * 1.4 + 34, 17, colour)
  }
  if (who.cards) row(pen, who.cards, centre, y + 26, width, who.won)
}

/**
 * Paints [card] onto [pen], which is expected to be [CARD_WIDTH] by
 * [CARD_HEIGHT]. [logo] is Banca's mark, or null if it could not be had, in
 * which case the card is drawn without it.
 */
export function drawHandCard(pen: Pen, card: HandCard, logo: CanvasImageSource | null) {
  const W = CARD_WIDTH
  const H = CARD_HEIGHT
  const mid = W / 2

  // The room, lit from the middle, and the felt lying in it.
  const [lit, , , dark] = roomColours()
  const room = pen.createRadialGradient(mid, H * 0.42, 0, mid, H * 0.42, H * 0.8)
  room.addColorStop(0, lit)
  room.addColorStop(1, dark)
  pen.fillStyle = room
  pen.fillRect(0, 0, W, H)
  const edge = pen.createRadialGradient(mid, H * 0.45, H * 0.3, mid, H * 0.45, H * 0.85)
  edge.addColorStop(0, 'rgb(0 0 0 / 0)')
  edge.addColorStop(1, 'rgb(0 0 0 / 0.55)')
  pen.fillStyle = edge
  pen.fillRect(0, 0, W, H)
  rounded(pen, 36, 36, W - 72, H - 72, 64)
  pen.strokeStyle = 'rgb(217 164 65 / 0.28)'
  pen.lineWidth = 2
  pen.stroke()

  // Whose table this was.
  if (logo) {
    pen.save()
    rounded(pen, mid - 44, 84, 88, 88, 20)
    pen.clip()
    pen.drawImage(logo, mid - 44, 84, 88, 88)
    pen.restore()
  }
  label(pen, "Banca · Texas Hold'em", mid, 222, 24, GOLD)

  // What happened, in a line, and what it was worth.
  pen.textAlign = 'center'
  pen.textBaseline = 'alphabetic'
  pen.fillStyle = card.tone === 'lost' ? IVORY : GOLD_BRIGHT
  // Kept to one line, however long, so that nothing below it has to move.
  let size = 78
  pen.font = `700 ${size}px ${SANS}`
  while (pen.measureText(card.headline).width > W - 150 && size > 36) {
    size -= 2
    pen.font = `700 ${size}px ${SANS}`
  }
  pen.fillText(card.headline, mid, 318)
  pen.font = `500 34px ${SANS}`
  pen.fillStyle = MUTED
  pen.fillText(card.detail, mid, 376)

  // The others' hands, where they were shown; then the board; then the player's own.
  const shown = card.others.filter((other) => other.cards)
  if (shown.length > 0) {
    const width = shown.length === 1 ? 118 : 96
    const spread = shown.length === 1 ? 0 : shown.length === 2 ? 330 : 310
    shown.forEach((other, index) => seat(pen, other, mid + (index - (shown.length - 1) / 2) * spread, 446, width, shown.length > 1))
  }

  const board = [...card.board, null, null, null, null, null].slice(0, 5)
  row(pen, board, mid, shown.length > 0 ? 672 : 560, 150, false)

  seat(pen, card.me, mid, shown.length > 0 ? 932 : 850, shown.length > 0 ? 136 : 150)

  // Banca's word on it, if it had one.
  if (card.quote) {
    pen.font = `italic 500 34px ${SERIF}`
    pen.fillStyle = IVORY
    pen.textAlign = 'center'
    pen.textBaseline = 'alphabetic'
    const said = wrapped(pen, `“${card.quote}”`, W - 240)
    said.slice(0, 2).forEach((line, index) => pen.fillText(line, mid, 1212 + index * 42 - (Math.min(said.length, 2) - 1) * 24))
  }

  label(pen, `${location.host} · play money only`, mid, 1284, 20, MUTED)
}

function loaded(src: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => resolve(null)
    image.src = src
  })
}

/** The card as a picture file, or null in a browser that cannot make one. */
export async function pictureOf(card: HandCard): Promise<File | null> {
  const canvas = document.createElement('canvas')
  canvas.width = CARD_WIDTH
  canvas.height = CARD_HEIGHT
  const pen = canvas.getContext('2d')
  if (!pen || typeof pen.roundRect !== 'function') return null

  drawHandCard(pen, card, await loaded('/logo-192.png'))
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  return blob && new File([blob], `banca-hand-${card.handNumber}.png`, { type: 'image/png' })
}
