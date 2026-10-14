/** "1st", "2nd", "3rd", "4th". */
export function ordinal(place: number): string {
  const tens = place % 100
  if (tens >= 11 && tens <= 13) return `${place}th`
  return `${place}${['th', 'st', 'nd', 'rd'][place % 10] ?? 'th'}`
}
