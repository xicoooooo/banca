const SUITS: Record<string, { symbol: string; name: string; red: boolean }> = {
  s: { symbol: '♠', name: 'spades', red: false },
  h: { symbol: '♥', name: 'hearts', red: true },
  d: { symbol: '♦', name: 'diamonds', red: true },
  c: { symbol: '♣', name: 'clubs', red: false },
}

const RANKS: Record<string, string> = { T: '10', J: 'J', Q: 'Q', K: 'K', A: 'A' }

const SHAPE = 'h-20 w-14 sm:h-24 sm:w-16 rounded-lg shadow-md shrink-0'

export function PlayingCard({ card }: { card: string }) {
  const rank = RANKS[card[0]] ?? card[0]
  const suit = SUITS[card[1]]

  return (
    <div
      role="img"
      aria-label={`${rank} of ${suit.name}`}
      className={`${SHAPE} bg-white flex flex-col items-center justify-center font-semibold ${
        suit.red ? 'text-red-600' : 'text-slate-900'
      }`}
    >
      <span className="text-2xl leading-none">{rank}</span>
      <span className="text-2xl leading-none">{suit.symbol}</span>
    </div>
  )
}

export function CardBack() {
  return (
    <div
      role="img"
      aria-label="Face-down card"
      className={`${SHAPE} border-2 border-white/80 bg-[repeating-linear-gradient(45deg,#7f1d1d,#7f1d1d_6px,#991b1b_6px,#991b1b_12px)]`}
    />
  )
}

export function CardSlot() {
  return <div aria-hidden className={`${SHAPE} border-2 border-dashed border-white/15 shadow-none`} />
}
