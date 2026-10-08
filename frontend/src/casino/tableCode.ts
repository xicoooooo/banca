/** A private table's code as it is shown and read out: capitals, in two groups of three. */
export function shownCode(id: string): string {
  const code = id.toUpperCase()
  return code.length === 6 ? `${code.slice(0, 3)} ${code.slice(3)}` : code
}

/** What a player typed, reduced to what a code can hold. Spaces, dashes and case are theirs to use or not. */
export function typedCode(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 6)
}
