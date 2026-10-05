# Table protocol

How a client talks to a live table. Version 1, JSON text frames over a WebSocket.

## Connecting

```
ws://<host>/ws/table
```

Each connection gets a private heads-up poker table: the person who connected sits in seat 0 against a driven opponent in seat 1. A hand is dealt immediately and the first `state` message follows.

Shared tables and sign-in are not part of this version.

## Server to client

### `state`

Sent after every change. It is always the complete view for your seat, so a client can render from the latest message alone and never needs to apply deltas.

```json
{
  "type": "state",
  "view": {
    "handNumber": 1,
    "street": "flop",
    "board": ["Ah", "Td", "Ks"],
    "pot": 120,
    "buttonSeat": 0,
    "smallBlind": 10,
    "bigBlind": 20,
    "yourSeat": 0,
    "actorSeat": 0,
    "players": [
      { "seat": 0, "name": "You", "stack": 1940, "committed": 0, "status": "active", "cards": ["Jh", "Ts"] },
      { "seat": 1, "name": "Banca", "stack": 1940, "committed": 0, "status": "active", "cards": null }
    ],
    "legal": {
      "canFold": true, "canCheck": true, "canCall": false, "callCost": 0,
      "canBet": true, "minBet": 20,
      "canRaise": false, "minRaiseTo": 20, "maxTo": 1940
    },
    "result": null
  }
}
```

| Field | Meaning |
|---|---|
| `street` | `preflop`, `flop`, `turn`, `river` or `showdown` |
| `board` | Community cards revealed so far |
| `pot` | Everything paid into the hand, including bets on the current street |
| `actorSeat` | Whose turn it is, or `null` when the hand is over |
| `players[].committed` | Chips that player has put in on the current street |
| `players[].status` | `active`, `folded` or `all_in` |
| `players[].cards` | Your own cards always. Another player's only after a showdown they took part in, otherwise `null` |
| `legal` | What you may do. Present only when `actorSeat` is your seat |
| `result` | Present only once the hand is over |

Cards are two characters, rank then suit: `2`-`9`, `T`, `J`, `Q`, `K`, `A` and `c`, `d`, `h`, `s`.

`legal.minBet`, `legal.minRaiseTo` and `legal.maxTo` are totals to have in front of you on this street, not increments. `maxTo` is all-in.

When the hand ends:

```json
"result": {
  "winnings": { "0": 360 },
  "showdown": { "0": "two_pair", "1": "pair" }
}
```

`winnings` maps seat to chips collected from the pot. `showdown` maps seat to hand category and is empty when the hand ended by everyone else folding.

### `error`

The last message could not be applied. The table is unchanged and the connection stays open.

```json
{ "type": "error", "message": "A raise to 30 is below the minimum of 40" }
```

## Client to server

### `act`

```json
{ "type": "act", "action": "fold" }
{ "type": "act", "action": "check" }
{ "type": "act", "action": "call" }
{ "type": "act", "action": "bet", "amount": 60 }
{ "type": "act", "action": "raise", "amount": 120 }
```

`amount` is the total to have in front of you on this street, matching the `legal` bounds. Calling for more than you hold puts you all-in for what you have.

### `next_hand`

```json
{ "type": "next_hand" }
```

Deals the next hand once the current one is over. The button moves one seat. If a player is out of chips, both stacks are reset.

## Rules the server enforces

The client renders and never decides. Every action is validated on the server, and anything illegal is answered with an `error`. No message ever carries cards the receiving seat is not entitled to see.
