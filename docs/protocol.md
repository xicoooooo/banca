# Table protocol

Two games are served, each at its own address: [poker](#connecting) and [blackjack](#blackjack). They share one rule: the client draws what it is sent and never decides an outcome.

## Poker

How a client talks to a live table. Version 1, JSON text frames over a WebSocket.

## Connecting

```
ws://<host>/ws/table
```

Each connection gets a private heads-up poker table: the person who connected sits in seat 0 against an agent in seat 1. A hand is dealt immediately and the first `state` message follows.

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

### `trace`

A step the opponent took while deciding, sent as it happens.

```json
{ "type": "trace", "handNumber": 1, "event": { "kind": "tool", "label": "Estimated its hand equity", "detail": null } }
```

`kind` is `tool`, `thought`, `decision` or `fallback`. `label` is safe to show at once. `detail` is always `null` here: what a step returned can give the opponent's cards away, so it is held back while the hand is live.

### `reveal`

The opponent's full reasoning for a hand, sent once, straight after the `state` that ends it. The same events as the `trace` messages, now with their `detail`.

```json
{
  "type": "reveal",
  "handNumber": 1,
  "events": [
    { "kind": "tool", "label": "Estimated its hand equity", "detail": "get_hand_equity → {\"equity\":0.522, ...}" },
    { "kind": "decision", "label": "Decided to check", "detail": null }
  ]
}
```

Not sent for a hand in which the opponent never had to decide.

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

---

# Blackjack

Version 1, JSON text frames over a WebSocket, at its own address:

```
ws://<host>/ws/blackjack
```

Each connection gets a private table: one player against the house, with 2,000 chips, bets from 10 to 500, six decks, and a dealer who stands on every seventeen. Nothing is dealt until a bet is placed, so the first `state` shows a table waiting for one.

## Server to client

### `state`

Sent after every change, and always complete.

```json
{
  "type": "state",
  "view": {
    "roundNumber": 3,
    "phase": "player",
    "stack": 1900,
    "minBet": 10,
    "maxBet": 500,
    "lastBet": 100,
    "dealer": { "cards": ["7d", null], "total": 7, "soft": false },
    "hands": [
      { "cards": ["9s", "8c"], "bet": 100, "total": 17, "soft": false, "status": "playing", "outcome": null, "returned": null }
    ],
    "activeHand": 0,
    "legal": { "bet": false, "hit": true, "stand": true, "double": true, "split": false, "insurance": false },
    "insuranceCost": 0,
    "result": null
  }
}
```

| Field | Meaning |
|---|---|
| `phase` | `betting` before the first round, then `insurance`, `player` or `settled` |
| `stack` | Chips not on the table. Bets have already left it; winnings return when the round settles |
| `lastBet` | What was staked last round, to offer again |
| `dealer.cards` | In the order dealt. The hole card is `null` until the round is settled |
| `dealer.total` | The total of the cards that can be seen |
| `hands` | One hand, or more after a split, in the order they are played |
| `hands[].status` | `playing`, `waiting`, `stood`, `doubled`, `bust` or `blackjack` |
| `hands[].outcome` | Once settled: `blackjack`, `win`, `push`, `lose` or `bust` |
| `hands[].returned` | Once settled: every chip that came back for the hand, the stake included |
| `activeHand` | Which hand is being played, or `null` |
| `legal` | What may be done now. `bet` is true whenever a new round can start |
| `insuranceCost` | Half the bet, while insurance is on offer |
| `result` | Once settled: `net` (what the round did to your chips), `insuranceReturned`, and `refilled` |

`soft` means an ace is being counted as eleven. `result.refilled` is true when the round left you unable to make the smallest bet and the house staked you again.

A dealer showing an ace offers insurance before anything else: the phase is `insurance` and only `insure` or `decline_insurance` is accepted. A natural on either side settles the round at once, so a `state` straight after a bet can already be `settled`.

### `error`

As for poker: the last message could not be applied, the table is unchanged, and the connection stays open.

## Client to server

```json
{ "type": "bet", "amount": 100 }
{ "type": "act", "action": "hit" }
{ "type": "act", "action": "stand" }
{ "type": "act", "action": "double" }
{ "type": "act", "action": "split" }
{ "type": "act", "action": "insure" }
{ "type": "act", "action": "decline_insurance" }
```

`bet` starts a round and is accepted only when `legal.bet` is true. Doubling and splitting each put a second stake of the same size on the table. Split aces receive one card each and are then finished.

## Rules the server enforces

A natural pays three to two. Insurance pays two to one. Twenty-one made after a split is an ordinary twenty-one. Any two cards worth the same may be split, up to four hands, and a split hand may be doubled. The dealer does not draw when every hand has bust.
