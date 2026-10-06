// What every shared table has, whatever is played at it: people, and what they say.

/** Someone at a shared table, as the others see them. `net` is what the last round did for them, once it is over. */
export type RoomPlayer = { name: string; staked: number; net: number | null; you: boolean }

/** Something said at a table: a set phrase, or a message a player typed, as tidied by the server. */
export type ChatLine = { from: string; text: string; emote: boolean }

export type Phrase = { id: string; text: string; emote: boolean }

/** A shared table as the list of them shows it. */
export type TableListing = { id: string; name: string; players: number }
