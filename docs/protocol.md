# Table protocol

Three games are served, each at its own address: [poker](#connecting), [blackjack](#blackjack) and [roulette](#roulette). All three can also be played with other people: poker and blackjack at shared tables ([poker](#poker-tables), [blackjack](#blackjack-tables)) and roulette in [rooms](#roulette-rooms). They share two rules: the client draws what it is sent and never decides an outcome, and nothing is dealt until the client has said [who is playing](#players).

## Players

A player's chips, history and statistics belong to the player and are kept by the server. A table only borrows the bankroll while the player sits at it.

Every player starts as a guest. The server makes one on request and returns a secret token, once; the client keeps it and presents it from then on. Only a hash of the token is stored. A guest's profile can be reached only from the browser that holds its token, until they [sign in](#signing-in).

| Request | Answer |
|---|---|
| `POST /players` | `201` `{ "token": "...", "player": { "name": "Guest 4821", "balance": 2000, "signedIn": false } }` |
| `GET /players/me` | `{ "name": "...", "balance": 1940, "signedIn": false }` |
| `PATCH /players/me` with `{ "name": "Ana" }` | The same, renamed. `400` with `{ "message": "..." }` if the name is refused |
| `GET /players/me/dashboard` | Everything the profile page shows, worked out from the player's rounds |

All but the first need `Authorization: Bearer <token>`, and answer `401` to a token the server does not know.

A new player is granted 2,000 chips, once. After that the balance changes as rounds are won and lost, and by the two [rewards](#rewards) below. Every change is a line in a ledger and the balance is their sum.

### Rewards

Chips cannot be bought, so these are the only ways to come by chips without winning them. Both are worked out from the ledger; nothing else is stored.

- **The daily reward** is claimed once a day, with days counted in UTC. Each day claimed in a row is worth more, through a week of 200, 300, 400, 500, 750, 1,000 and 2,000, which then starts over. Missing a day starts it over too.
- **The house's stake** is 500 chips, given when a player sits down to a round they cannot cover, and at most once every four hours.

| Request | Answer |
|---|---|
| `POST /players/me/rewards/daily` | `{ "granted": 200, "balance": 2200, "rewards": { ... } }`. `409` if today's has been claimed |

What is on offer is the `rewards` part of the dashboard: for `daily`, whether it is `available`, its `amount`, the `day` of the week it is, the `streak`, and `nextAt`; for `rescue`, its `amount` and `nextAt`, null when the house would stake the player now.

A table tells the player about their chips with two messages of its own, the same in every game:

```json
{ "type": "staked", "amount": 500 }
{ "type": "broke", "dailyReady": false, "nextChipsAt": "2026-10-06T16:00:00Z" }
```

`staked` says the house has just staked them, so chips do not appear unexplained. `broke` says nothing will be dealt: they cannot cover the smallest bet and the house will not stake them yet. `dailyReady` is true when claiming the daily reward would get them playing again; otherwise `nextChipsAt` is when the next chips of either kind arrive. Asking again (`next_hand` in poker, a `bet` in blackjack, a `spin` in roulette) is answered with a deal once they can cover it.

### Signing in

Signing in saves a profile to an account so it can be reached from any device. Accounts are held by Supabase Auth, and Google is the only way in for now. The provider is used only to establish who the player is; after that the server's own token stands for them, exactly as for a guest.

| Request | Answer |
|---|---|
| `GET /sign-in` | `{ "url": "...", "publicKey": "..." }`, what the browser needs to send the player to the provider. Both null when signing in is not set up |
| `POST /players/me/account` with `{ "accessToken": "..." }` | `{ "token": null, "player": { ... } }`. `403` if the provider does not confirm the access token |
| `DELETE /players/me/session` | `204`. The token used for the request stops working; the profile stays with its account |

The browser sends the player to the provider, comes back with a code, trades it for an access token, and posts that with its own bearer token. The server asks the provider whose access token it is, then:

- **The account has no profile yet.** It takes the profile the browser already has, so nothing a guest has won or played is lost. `token` is null and the browser carries on with the one it had.
- **The account already has a profile.** `token` is a new one for that profile, and the browser must use it from then on. The guest profile it arrived with is left behind: two bankrolls are never added together, or making guests would be a way of making chips.

### Tables outlive connections

A table belongs to the server, not to the wire. Each player has one table of their own at each game, and a connection only attaches to it.

- **Dropping and coming back.** If the connection is lost, the table waits. Connecting again within three minutes is answered with a `state` showing the table exactly as it was left: the same cards, the same bets, whose turn it is. Nothing is settled by dropping.
- **One place at a time.** Opening the same table from a second tab or device takes it over. The earlier connection is sent `{ "type": "error", "code": "replaced", "message": "..." }` and closed, and should not try to come back.
- **Walking away.** A table nobody returns to in time is cleared, and a round still in play is finished on the player's behalf in the way that risks nothing more: a poker hand is folded, a blackjack hand stands and declines insurance. It is then written to their record like any other, so leaving is never a way out of losing a round.

Tables are kept in the server's memory. If the server itself restarts, rounds in play are lost and nothing is charged for them.

### Saying hello

The first frame on either WebSocket must be:

```json
{ "type": "hello", "token": "..." }
```

The server answers `{ "type": "welcome", "player": { "name": "...", "balance": 1940 } }` and the game begins. A token it does not know is answered with `{ "type": "error", "code": "unknown_player", "message": "..." }` and the connection is closed; the client should ask for a new guest and connect again.

## Poker

How a client talks to a live table. Version 1, JSON text frames over a WebSocket.

## Connecting

```
ws://<host>/ws/table
```

Each player has a private heads-up poker table: they sit in seat 0 against an agent in seat 1. Once the client has [said hello](#saying-hello), a hand is dealt and the first `state` message follows.

The table is a cash game. Each hand the player sits down with their bankroll, up to 2,000 chips, against 2,000 for the agent, and what they win or lose in the hand is written to their bankroll as it ends.

Shared tables are not part of this version.

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

Each player has a private table: one player against the house, playing from their own bankroll, with bets from 10 to 500, six decks, and a dealer who stands on every seventeen. Nothing is dealt until a bet is placed, so the first `state` after the [hello](#saying-hello) shows a table waiting for one. `stack` is the player's bankroll, and each round's result is written to it as the round settles.

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

`soft` means an ace is being counted as eleven. `result.refilled` is true when the round left you unable to make the smallest bet and the house staked you.

A dealer showing an ace offers insurance before anything else: the phase is `insurance` and only `insure` or `decline_insurance` is accepted. A natural on either side settles the round at once, so a `state` straight after a bet can already be `settled`.

### `trace` and `advice`

Sent only after the player asks the coach with `advise`.

```json
{ "type": "trace", "roundNumber": 3, "event": { "kind": "tool", "label": "Worked out what each play is worth", "detail": "get_action_values → {...}" } }
{
  "type": "advice",
  "roundNumber": 3,
  "hand": 0,
  "advice": {
    "action": "hit",
    "reason": "The dealer busts only 23% of the time from a jack, so standing on 15 loses more than hitting does.",
    "values": [{ "action": "hit", "value": -0.504 }, { "action": "stand", "value": -0.54 }],
    "source": "banca"
  }
}
```

Each `trace` is a step the coach took, sent as it happens and with its detail. Unlike the poker opponent's, nothing is held back: the coach is given only the player's own view of the table, so it has nothing to give away. `values` is what every play open to the player is expected to return per chip bet, the best first, and the advised `action` is always the first of them. `source` is `banca` when the model put the reason into words and `book` when it could not and the reason was made from the figures.

### `error`

As for poker: the last message could not be applied, the table is unchanged, and the connection stays open.

## Client to server

```json
{ "type": "advise" }
{ "type": "bet", "amount": 100 }
{ "type": "act", "action": "hit" }
{ "type": "act", "action": "stand" }
{ "type": "act", "action": "double" }
{ "type": "act", "action": "split" }
{ "type": "act", "action": "insure" }
{ "type": "act", "action": "decline_insurance" }
```

`advise` asks the coach about the decision in front of the player, and is refused when there is none. The answer arrives in its own time; the player may act without waiting, and advice for a decision already made is never sent. Asking again about the same decision repeats the same advice.

`bet` starts a round and is accepted only when `legal.bet` is true. Doubling and splitting each put a second stake of the same size on the table. Split aces receive one card each and are then finished.

## Rules the server enforces

The coach's advice is never taken on trust. What each play is worth is worked out on the server from the rules, and advice from the model is accepted only if it is a play open to the player and as good as any other. A model that fails, stalls or will not settle is replaced by the figures.

A natural pays three to two. Insurance pays two to one. Twenty-one made after a split is an ordinary twenty-one. Any two cards worth the same may be split, up to four hands, and a split hand may be doubled. The dealer does not draw when every hand has bust.

---

# Roulette

Version 1, JSON text frames over a WebSocket, at its own address:

```
ws://<host>/ws/roulette
```

Each player has a private table: one player against a European wheel, with a single zero, playing from their own bankroll. The first `state` after the [hello](#saying-hello) shows a table waiting for bets.

Roulette has no decisions once the bets are down, so a whole round is one message each way. The player builds a layout of chips in the client, sends it with `spin`, and the answer says where the ball landed and what each bet came to.

## Client to server

```json
{
  "type": "spin",
  "bets": [
    { "kind": "straight", "number": 17, "amount": 10 },
    { "kind": "red", "amount": 50 },
    { "kind": "dozen", "number": 2, "amount": 50 }
  ]
}
```

| `kind` | Covers | Pays | `number` |
|---|---|---|---|
| `straight` | One number | 35 to 1 | The number, 0 to 36 |
| `split` | Two numbers that touch | 17 to 1 | One number, with the other in `other` |
| `street` | A row of three | 11 to 1 | The lowest: 1, 4, 7 … |
| `corner` | Four numbers that meet | 8 to 1 | The lowest |
| `six_line` | Two rows of three | 5 to 1 | The lowest: 1, 4, 7 … |
| `dozen` | 1–12, 13–24 or 25–36 | 2 to 1 | Which: 1, 2 or 3 |
| `column` | A column of twelve | 2 to 1 | Which: 1 holds 1, 4, 7 … |
| `red` `black` `even` `odd` `low` `high` | Eighteen numbers | 1 to 1 | |

`analyse` takes the same `bets` as `spin` and asks Banca what it makes of them, without spinning or staking anything. The layout must be one the table would take. The answer arrives in its own time; the player may spin without waiting, and a read of a layout already spun is never sent. Asking again about the same chips repeats the same read.

## Server to client

### `state`

```json
{
  "type": "state",
  "view": {
    "roundNumber": 1,
    "stack": 1695,
    "minBet": 10,
    "maxInside": 100,
    "maxOutside": 500,
    "history": [15],
    "result": {
      "pocket": 15,
      "color": "black",
      "wagers": [
        { "kind": "straight", "number": 17, "other": null, "amount": 10, "returned": 0 },
        { "kind": "red", "number": null, "other": null, "amount": 50, "returned": 0 },
        { "kind": "dozen", "number": 2, "other": null, "amount": 50, "returned": 150 }
      ],
      "staked": 110,
      "net": 40,
      "refilled": false
    }
  }
}
```

`returned` is every chip coming back for a bet, the stake included, and nought for a bet that lost. `history` is where the ball has landed lately, newest first. `result.refilled` is true when the spin left the player unable to make the smallest bet and the house staked them. Nothing in the view is hidden, because roulette has nothing to hide.

The result is in the same message as the spin: the server decides where the ball lands before the client's wheel begins to turn, and the client only takes its time showing it.

### `trace` and `read`

Sent only after the player asks about a layout with `analyse`.

```json
{ "type": "trace", "event": { "kind": "tool", "label": "Worked out your chances", "detail": "get_chances → {...}" } }
{
  "type": "read",
  "read": {
    "text": "Your red and black bets cancel each other out. You come out ahead on about 3% of spins, and on average this layout costs you 0.8 chips a spin.",
    "figures": { "staked": 30, "ahead": 0.027, "level": 0, "behind": 0.946, "nothing": 0.027, "best": 350, "bestPockets": [17], "average": -0.81 },
    "source": "banca"
  }
}
```

`figures` is worked out on the server by settling the layout on every one of the 37 pockets: the share of them on which the player comes out `ahead`, exactly `level`, `behind` with something back, or with `nothing`; the `best` a spin could do and where; and what the layout comes to on `average`, which is always a loss of one part in 37 of what is staked. `text` is how Banca puts it. `source` is `banca` when the model chose the words and `book` when it could not and the text was made from the figures.

### `error`

As for the other games: the message could not be applied, nothing was spun, and the connection stays open.

## Rules the server enforces

A bet covering *n* numbers pays 36 / *n* − 1 to one, which gives every payout above and leaves the house the same edge, one part in 37, on all of them. Zero is neither red nor black, even nor odd, low nor high.

Banca has no bet to recommend, because there is none: the tools it reads a layout through give it the figures above and nothing else. It is never shown where the ball has landed before, so it has nothing from which to suggest that a number is due.

A layout is taken whole or refused whole. Every bet must be at least `minBet`; a bet on the numbers themselves (`straight` to `six_line`) at most `maxInside`, and any other at most `maxOutside`. Chips on the same bet are counted together. The layout may not come to more than the player has.

---

# Roulette rooms

Version 1, JSON text frames over a WebSocket. Besides the private table above, roulette is played in shared rooms, each at its own address:

```
ws://<host>/ws/roulette/rooms/<room>
```

`GET /roulette/rooms` lists them, with how many players are in each and where its ball has landed lately:

```json
[{ "id": "emerald", "name": "Emerald Room", "players": 2, "history": [17, 10] }]
```

A room is one wheel shared by everyone in it, and it keeps time for them all. Each round has three phases: `betting`, while chips may go down; `spinning`, from the moment bets close until the ball is seen to land; and `results`. Then the next round opens. With the default timings a round is half a minute. A room with nobody in it stops, and starts again when someone walks in.

Each player's chips are still their own. The room holds a player's bets until the spin, settles them against the same pocket as everyone else's, and writes the result to that player's record exactly as a private table would. The rules and limits are those of the private table.

## Client to server

```json
{ "type": "bets", "bets": [{ "kind": "red", "amount": 50 }] }
{ "type": "chat", "say": "good_luck" }
{ "type": "analyse", "bets": [{ "kind": "red", "amount": 50 }] }
```

`bets` is the player's whole layout for the round, replacing whatever they had down; an empty list takes it all back. It is sent every time the layout changes, so the room always holds what the player sees, and is refused once bets have closed. There is no message to spin: the room does that.

`chat` says something to the room: either one of its set phrases, named by its id in `say`, or a message the player typed, in `text`:

```json
{ "type": "chat", "text": "Anyone else on black?" }
```

A typed message is tidied by the server before anyone sees it: made one line, cut to 140 characters, links replaced with `[link]`, and a short list of the most offensive words starred out. One line every second and a half at most. Nobody moderates a room, so the client lets each player mute anyone they would rather not hear from; muting is the listener's business and the server is not told.

`analyse` is as at the private table.

## Server to client

### `state`

```json
{
  "type": "state",
  "view": {
    "room": "emerald",
    "name": "Emerald Room",
    "roundNumber": 3,
    "phase": "betting",
    "msLeft": 14200,
    "stack": 1950,
    "minBet": 10,
    "maxInside": 100,
    "maxOutside": 500,
    "history": [17, 10],
    "pocket": null,
    "bets": [{ "kind": "red", "amount": 50 }],
    "crowd": [
      { "kind": "red", "number": null, "other": null, "amount": 50, "players": 1 },
      { "kind": "straight", "number": 8, "other": null, "amount": 10, "players": 1 }
    ],
    "players": [
      { "name": "Ana", "staked": 50, "net": null, "you": true },
      { "name": "Marta", "staked": 10, "net": null, "you": false }
    ],
    "result": null
  }
}
```

Sent to everyone in the room whenever the phase changes or anyone's bets do, each from their own seat. `msLeft` is how long the phase has to run by the server's clock; the client counts down from when the message arrived. `stack` leaves out chips the player has on the felt. `crowd` is the chips on each bet, everyone's together.

`pocket` is where the ball is landing, from the moment bets close. The server has decided it by then and the player's `result` and record are already settled, but nothing that would give it away early is shown: until `results`, `stack` is still what the player had with their bets down, the pocket is not yet in `history`, and no player's `net` is set. In `results`, `players[].net` shows how everyone did.

### `chat_log` and `chat`

```json
{ "type": "chat_log", "lines": [{ "from": "Marta", "text": "Good luck", "emote": false }], "phrases": [{ "id": "good_luck", "text": "Good luck", "emote": false }] }
{ "type": "chat", "line": { "from": "Ana", "text": "👏", "emote": true } }
```

`chat_log` is sent on walking in: what has been said lately, and the phrases that can be said with one press. `chat` is a line as it is spoken, already tidied if it was typed.

### `trace`, `read` and `error`

As at the private table.

## Dropping and leaving

A bet made is a bet made. If a player's connection drops, their chips stay on the felt and are played at the next spin. Coming back shows them the room as it stands, their result included. A player who does not come back is shown out once nothing of theirs is riding.

---

# Blackjack tables

Version 1, JSON text frames over a WebSocket. Besides the private table, blackjack is played at shared tables, each at its own address:

```
ws://<host>/ws/blackjack/tables/<table>
```

`GET /blackjack/tables` lists them: `[{ "id": "emerald", "name": "Emerald Table", "players": 2, "seats": 5 }]`.

A shared table is one dealer and one shoe, and up to five players with a hand each. The table keeps the time:

1. **`betting`**: each player may put down a stake for the coming round. If nobody does, there is no round, and another betting window opens.
2. **`insurance`**: only when the dealer shows an ace. Everyone dealt in answers at once; anyone who has not answered in time has declined.
3. **`playing`**: the players act one at a time, in the order they sat down, each with a limit on how long they may take over a decision. A player who runs out of time has that hand stood for them. A player whose connection has dropped is waited for only briefly.
4. **`results`**: the dealer's hand is played once and every player is settled against it. Then betting opens again.

The rules are those of the private table. Each player's stake, result and record are their own.

## Client to server

```json
{ "type": "bet", "amount": 50 }
{ "type": "act", "action": "stand" }
{ "type": "advise" }
{ "type": "chat", "text": "Evening all" }
```

`bet` sets the player's stake for the coming round and is accepted only while betting is open; an amount of nought takes it back. `act` takes the same actions as the private table, and is refused unless it is the player's turn, or an answer about insurance while that is being asked. `advise` and `chat` are as elsewhere.

## Server to client

### `state`

```json
{
  "type": "state",
  "view": {
    "room": "emerald",
    "name": "Emerald Table",
    "roundNumber": 4,
    "phase": "playing",
    "msLeft": 18400,
    "yourTurn": true,
    "actor": "Ana",
    "you": { "roundNumber": 4, "phase": "player", "stack": 1950, "hands": [], "dealer": {}, "legal": {}, "result": null },
    "seats": [
      { "name": "Ana", "you": true, "bet": 50, "hands": [{ "cards": ["3h", "Ts"], "total": 13, "status": "playing" }], "acting": true, "net": null },
      { "name": "Marta", "you": false, "bet": 25, "hands": [{ "cards": ["4c", "3d"], "total": 7, "status": "waiting" }], "acting": false, "net": null }
    ],
    "seatsInAll": 5
  }
}
```

`you` is the player's own part in the round, in exactly the shape the private table's `view` has, so a client can draw it the same way. Its `legal` plays are only ever true on the player's own turn. Its `phase` can also be `waiting`: the player has finished or is sitting the round out, and others are still playing. A natural is paid on the deal, so `you.result` can be present while the table is still `playing`.

`you.dealer` is the same for every player at the table. The hole card is null until the table reaches `results`, however any one player's round stands, so that a player who has finished cannot tell the others what the dealer holds.

`seats` is everyone at the table in the order they sat down, with their cards face up, as they are at a real table. `net` is set in `results`. `msLeft` is how long the table will wait in this phase, or for the player whose turn it is.

### `chat_log`, `chat`, `trace`, `advice` and `error`

As elsewhere. Sitting down at a table with no seat free is answered with `{ "type": "error", "code": "full", "message": "..." }`.

## Dropping and leaving

A stake put down is played. If a player's connection drops mid-round, their hand stays in play: when their turn comes it is stood after a few seconds, and it is settled with everyone else's. Coming back shows them the table as it stands. A player who does not come back gives up their seat once their round is over.

---

# Poker tables

Version 1, JSON text frames over a WebSocket. Besides the table for one, Hold'em is played at shared tables, each at its own address:

```
ws://<host>/ws/poker/tables/<table>
```

`GET /poker/tables` lists them: `[{ "id": "emerald", "name": "Emerald Table", "players": 2, "seats": 5 }]`. `seats` is the seats for players; Banca always has one more.

A shared table has six seats. Banca sits in seat 0 at every table and plays as it does heads up: from its own view of the hand, by calling tools. Up to five players take the others. It is a cash game, as the table for one is: each hand a player sits down with their bankroll, up to 2,000 chips, and what the hand wins or loses is written to their bankroll as it ends. Blinds are 10 and 20.

Hands follow one another without anyone asking. A player who sits down while a hand is being played watches it and is dealt into the next. Every decision has a time limit: a player who runs out of time checks if that costs nothing and folds if it does.

## Client to server

```json
{ "type": "act", "action": "raise", "amount": 120 }
{ "type": "chat", "text": "Nice hand" }
```

`act` is as at the table for one, and is refused unless it is the player's turn. There is no `next_hand`: the table deals.

## Server to client

### `state`

```json
{
  "type": "state",
  "view": {
    "room": "emerald",
    "name": "Emerald Table",
    "phase": "playing",
    "msLeft": 23800,
    "yourTurn": true,
    "actor": "Ana",
    "dealtIn": true,
    "table": { "handNumber": 7, "street": "flop", "yourSeat": 1, "actorSeat": 1, "players": [], "legal": {}, "result": null },
    "seats": [
      { "seat": 0, "name": "Banca", "you": false, "inHand": true, "away": false },
      { "seat": 1, "name": "Ana", "you": true, "inHand": true, "away": false },
      { "seat": 2, "name": "Marta", "you": false, "inHand": false, "away": false }
    ],
    "seatsInAll": 6
  }
}
```

`table` is the hand as this player may see it, in exactly the shape the table for one sends, so a client can draw it the same way: their own cards, and anyone else's only at a showdown. `phase` is `playing` while a hand is live, `results` while a finished one is left on the table, and `waiting` when there is no hand to deal.

`dealtIn` is false for a player who is at the table but not in the hand, because they sat down part way through. Their `table` then has `yourSeat` of -1 and shows nobody's cards, and they are listed in `seats` with `inHand` false. `msLeft` is how long the table will wait for the player whose turn it is, or before the next hand.

### `trace` and `reveal`

As at the table for one, and sent to everyone at the table: each step Banca takes is shown as it happens with nothing private in it, and its full reasoning for the hand is sent once the hand is over.

### `chat_log`, `chat` and `error`

As elsewhere. Sitting down at a table with no seat free is answered with an `error` whose `code` is `full`.

## Dropping and leaving

A player whose connection drops mid-hand stays in it: when their turn comes they are checked or folded after a few seconds, and the hand is settled for them with everyone else. Coming back shows them the table as it stands. A player who is not there when a hand ends is not dealt into the next, and gives up their seat if they do not return.
