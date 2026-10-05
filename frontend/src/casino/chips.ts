export type ChipColor = 'emerald' | 'deep' | 'ivory' | 'gold'

export const CHIP_CLASS: Record<ChipColor, string> = {
  emerald: 'chip',
  deep: 'chip chip--deep',
  ivory: 'chip chip--ivory',
  gold: 'chip chip--gold',
}

/**
 * Which chips make up a bet of a given size, measured in big blinds. Bigger
 * bets are taller and reach for the richer colours, so a stack can be read at
 * a glance before the number is.
 */
export function chipsFor(amount: number, bigBlind: number): ChipColor[] {
  const blinds = amount / Math.max(1, bigBlind)
  if (blinds < 2) return ['emerald']
  if (blinds < 5) return ['emerald', 'emerald']
  if (blinds < 15) return ['emerald', 'deep', 'emerald']
  if (blinds < 50) return ['deep', 'ivory', 'emerald', 'deep']
  return ['gold', 'deep', 'ivory', 'gold', 'emerald']
}
