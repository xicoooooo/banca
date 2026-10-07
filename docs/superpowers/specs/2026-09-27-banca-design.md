# Banca - Design Spec

Date: 27/09/2026 · Revised: 07/10/2026
Author: Francisco Aragão Dias

> **About this revision.** The spec was written before any code. It has been brought into line with what is built: where the build chose differently from the first draft, the text now says what exists, and the choices are listed with their reasons in [section 14](#14-decisions-made-along-the-way). Where something is still to be built, it says so. The roadmap in [section 12](#12-roadmap) shows where each phase stands.

## 1. Purpose

Banca is a multi-game social casino played with virtual chips. One account, one chip balance, several table games: Texas Hold'em poker, blackjack, and roulette. Each game can be played alone or with other people, and at every table there is Banca: an agent driven by a large language model, which decides by calling tools over the Model Context Protocol (MCP). At poker it is an opponent; at blackjack a coach; at roulette an analyst. Its reasoning is streamed to the interface as it happens, so a player can watch the agent work out its decision.

The name is the Portuguese and Italian word for the house, the bank that runs the table.

**Goals, in priority order:**

1. Real users: friends can open a link and play within minutes, and come back the next day.
2. Recruiter signal: a deployed demo plus a clean, tested, documented monorepo showing real-time backend engineering, extensible game architecture, and applied agent/tool-use design.
3. Learning: deepen Kotlin/Ktor concurrency, WebSockets, MCP, and deployment skills.

**Non-goals:** real-money play of any kind, purchases of chips, native app-store builds, tournament infrastructure (stretch only), and any attempt to be a complete casino. Three games, done well, is the target.

**Timeline:** 4-8 months, solo, part-time alongside the MSc.

## 2. Hard rules

- **Virtual chips only.** Chips are granted free, cannot be bought, cannot be cashed out, and carry no value. This is a product rule, not a detail: it keeps the project clear of gambling regulation and app-store restrictions, and it is stated plainly in the README and the interface.
- **Zero budget.** Every service used must be free at the scale this project runs at, and free without a credit card wherever possible. Nothing gets designed in that carries a bill. Section 5 tracks this per service, and any future change to the stack has to answer to it.
- **The server owns the rules.** Clients render; they never decide outcomes. Every action, human or agent, is validated by the server. Rewards and streaks use server time, never the client clock.
- **Agents get no hidden information.** An agent sees exactly what a player in its seat would see. This is enforced in the tool layer and covered by tests.

## 3. Guiding decisions

- **Poker first, and no abstraction before it is earned.** Hold'em was built first, alone. The plan was to extract a shared game-module interface once blackjack gave it a second implementation to answer to. With all three games built, the engines turned out to have too little in common for one interface to be honest, so what is shared sits a layer up, where the games really are alike: see 8.4.
- **Walking skeleton before breadth.** The first milestone is one human against one agent at one poker table, end to end, with a rough interface. The agent is the hard part and the differentiator, so it exists early even if ugly.
- **One process, in memory.** Live tables live in the backend process; Postgres stores durable data. No message broker, no microservices, no horizontal scaling work until something actually demands it.
- **The model is pluggable.** The agent runtime talks to a `ModelProvider` interface, not to a vendor. Free providers are the default; a paid one can be switched on later by configuration alone.
- **Monorepo:** `backend/`, `frontend/`, `db/`, `docs/`.

## 4. Target platforms

One codebase: a responsive web application, installable as a PWA. A hand-written service worker keeps the page and the files it is built from, and nothing else: no table, player or chip is ever answered from the device. The page itself is always fetched fresh when there is a network, so a new release is picked up on the next visit without anyone clearing anything.

- **Phones (iOS and Android):** the PWA installs to the home screen and runs full screen. No app-store build, because an Apple Developer account costs 99 EUR/year and Google Play charges a 25 EUR registration fee, and neither is worth paying now.
- **Tablets and desktop browsers:** the same application, wider layout.
- **Installing:** the lobby offers it. Where the browser can install on request there is a button; on an iPhone, where it cannot, the player is told where Add to Home Screen is. Saying no is remembered on that device.
- **Offline:** the shell loads offline and says so across the top of the screen, but playing requires a connection, since the server owns the rules.

The interface is designed phone-first, since that is how a social casino is actually used.

## 5. Stack and cost control

Every entry is free at this project's scale. The right-hand column records the limit that matters and what happens when it bites.

| Layer | Choice | Free-tier reality |
|---|---|---|
| Backend | Kotlin + Ktor | Self-hosted code, no cost |
| Real-time | WebSockets (Ktor) | No cost |
| Backend hosting | Render free web service | No card needed. Spins down after ~15 min idle, cold start around 50 s. A GitHub Actions job pings `/health` every ten minutes to keep it warm, and the client shows a waking state when it is not |
| Database + auth | Supabase free tier | 500 MB, and projects pause after 7 days of inactivity. The keep-warm job reads the league table once a day, which reaches the database, so a quiet week does not pause it. New guests are limited by address so that nobody can fill the 500 MB by asking for them in a loop |
| Frontend hosting | Vercel Hobby | Free for non-commercial personal projects; this qualifies |
| Domain | none, use `*.vercel.app` | Paid domains are deferred. `banca.gg` and `getbanca.com` were free to register if that ever changes |
| Agent model | Ollama locally in development; Groq's free tier for the deployed demo | Free without a card, with tool calling. Rate limits are low and counted per model, so the deployed agent is given a list of models and falls through to the next when one is throttled. The allowance is one for everybody, so each player has a share of the coach, and past it is answered from the figures. The agent budget in 8.9 is designed around this |
| MCP | Self-written MCP servers on the official Kotlin SDK, run inside the backend process | No cost |
| Frontend | React + TypeScript + Vite + Tailwind, with animation in CSS and the Web Animations API | No cost |
| CI | GitHub Actions on a public repository | Unlimited minutes for public repos, which is one reason the repository is public |
| Avatars | The player's initial on a plate | No uploads, so no storage bill and no moderation burden |
| Sounds and fonts | Sounds synthesised in the browser, system fonts | No cost, and nothing to download |

**On the model provider:** Claude via the Anthropic API or Bedrock is not free, so it is not the default. This costs the project nothing architecturally, because MCP is an open protocol and tool calling is supported by the free providers above. The `ModelProvider` interface keeps Anthropic as a one-line switch if credits ever appear, and the README can honestly say the agent runs against any tool-calling model.

## 6. The three games

They differ in shape, which is exactly why the platform is interesting to build:

| Game | Shape | Against | Banca's role |
|---|---|---|---|
| Texas Hold'em | Turn-based, players against each other, hidden cards | Other seats | Opponent, with a seat at every table |
| Blackjack | Turn-based, each player against the dealer, dealer follows fixed rules | The house | Coach: asked before a decision, it says what it would do and what each play is worth |
| Roulette | Simultaneous betting, one spin resolves everything, many bets per player | The house | Analyst: asked before a spin, it says how often the layout wins and what it costs |

The dealer and the croupier are not agents. They follow fixed rules, so there is nothing for a model to decide, and a model that could decide would be a model that could cheat.

**Poker:** no-limit Hold'em cash game, blinds 10 and 20, a buy-in of up to 2,000 from the player's bankroll each hand.

**Blackjack:** six decks, dealer stands on every seventeen, a natural pays three to two, insurance two to one, split up to four hands, double after a split. Bets from 10 to 500.

**Roulette:** European single zero. Straight, split, street, corner, six line, dozen, column and the six even-money bets, each paying 36 / *n* − 1 to one for *n* numbers covered. From 10 a bet, up to 100 on the numbers and 500 outside.

Each game is played in three ways:

- **A private table**, opened by a player for their own company. It is a shared table like the listed ones in every way but one: it is on no list. Its address is the invitation, eight characters that cannot usefully be guessed, and whoever is sent the link sits down. It is cleared away once it has stood empty for half an hour.
- **A table to yourself**, at your own pace: heads-up against Banca at poker, alone against the dealer or the wheel at the others.
- **Shared tables**, three for each game (Emerald, Gold and Ivory), which keep their own time. A poker table seats Banca and up to five players, deals hand after hand, and gives each decision a limit. A blackjack table seats five against one dealer and one shoe, with a betting window and then a turn each. A roulette room is one wheel on a half-minute round, with everyone's chips on the same felt and the same history of where the ball has landed.

## 7. Player experience

What makes it feel like one app rather than three demos.

### 7.1 Identity
A visitor is playing within seconds: the server makes them a guest, with a name like "Guest 4821" and 2,000 chips, and the browser keeps a secret token that stands for them. Signing in with Google is optional and does one thing: it saves the profile to an account, so it can be reached from another device and takes a place in the leagues. A guest who signs in keeps everything they had. If the account already has a profile, that one is used and the guest's is left behind: two bankrolls are never added together, or making guests would be a way of making chips.

### 7.2 Profile and stats
Built. Display name, level and title, and the numbers that make a player want to play again: chips held, rounds played and won at each game, win rate, biggest win and biggest pot, winning and losing streaks, how they tend to play each game, recent rounds, achievements, and a chart of the bankroll over time. All of it is worked out from the ledger and the record of rounds, so none of it can disagree with what happened. How often the player's blackjack decisions were the best play is among them, from the grading in 7.7.

### 7.3 Daily rewards and bust protection
Built. Chips cannot be bought, so these are the only ways to come by them without winning.

**The daily reward** is claimed once a day, and each day in a row is worth more:

| Day | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|
| Chips | 200 | 300 | 400 | 500 | 750 | 1,000 | 2,000 |

After day 7 the week starts over, and so does a streak with a missed day. Days are counted on the server, in UTC. The first draft's ladder ran to 15,000 chips; against a starting bankroll of 2,000 and table limits of 500 that would have made playing well beside the point, so the week now comes to about two and a half bankrolls.

**The house's stake** is 500 chips, given when a player sits down to a round they cannot cover, at most once every four hours. It is given at the table, with a notice saying so, and a player who cannot be staked yet is told when their next chips arrive instead of being dealt nothing without a word.

Neither is stored as state. The streak and both clocks are read from the ledger, which is also what makes a claim impossible to pay twice.

### 7.4 Onboarding tutorial
Built. The first time a player opens a game's table for one, Banca walks them through a round of it: at poker, their cards, the pot, the actions, the opponent thinking and the coach; at blackjack, the bet, the hand, hit, stand and double, the coach and the review; at roulette, the chips, the layout, the read and the house edge. It can be skipped at any step and taken again from the profile.

It is a guide laid over a real round, not a scripted one. The round is dealt by the server like any other, the guide follows it by watching where it stands, and half its steps wait for the player to act. Steps that do not apply to the round dealt, such as insurance, are left out, and a round that ends early, by a natural or a fold, takes the guide with it. So nothing had to be built on the server, and what a player learns on is the game itself. Where it says to ask Banca, the coach they ask is the real one (7.7).

What it says is written, not generated: a first-time player should get the same clear explanation every time, at no cost to the free tier. Whether a guide has been seen is kept in the browser, so a new device offers it again. The shared tables have no guide, since they run on a clock that will not wait for one.

### 7.5 Levels and achievements
Built, earlier than planned, because the profile was thin without them. Every round earns experience, more for a win, a natural or a showdown won, and each level asks for a hundred more than the last. Levels carry a title, from Newcomer upwards. Fourteen achievements mark things done at the tables. Levels do not yet unlock anything; higher-stake tables gated by level remain a stretch.

### 7.6 Leagues, trophies and public pages
Built, and more than the leaderboard first planned. Only players who have signed in take part, which keeps a throwaway guest from being a way onto the board.

- **Five leagues**, Bronze, Silver, Gold, Platinum and Emerald. Everyone starts in Bronze.
- **A week** runs Monday to Monday in UTC. Players are ranked within their league by what they won at the tables that week, all games together. Rewards and prizes do not count.
- **At the end of the week** the top three go up a league, if they played at least ten rounds and finished ahead, and are paid 1,000, 500 and 250 chips, multiplied by the league's rank. A player above Bronze who did not play goes down, and so do the bottom three of a league where at least ten played.
- **Trophies.** First, second and third also keep a trophy for good: "Gold Champion", "Silver Runner-up", "Bronze Third place", with the week it was won.
- **Leaderboards** beside the league: the biggest winners this week and of all time, at each game or all of them.
- **A public page** for every signed-in player, opened from their name on any board: name, level, league, rounds played, achievements earned and trophies. Never their balance, email or history.

Nothing runs on a timer. A finished week is settled once, by the first request to look at a league after it ends.

### 7.7 Coach mode
Partly built. This is the feature that separates Banca from an ordinary social casino: the same agent that plays against you can teach you, and its reasoning is visible either way.

- **Before acting, built at blackjack and roulette.** At blackjack the player asks and Banca answers with a play, a reason, and what every play open to them is worth. Those figures are computed on the server from the rules, and the model's advice is passed on only if it agrees with them; otherwise the figures answer in its place. At roulette the answer is a read of the layout: how often it comes out ahead, the best it can do, and what it costs on average.
- **After the round, built at blackjack.** Every decision the player made is graded against the same figures: the best play, a slip, or a mistake, with what it gave up in chips and why the better play was better. It grades the choice and never the outcome, and it is computed on the server with no model involved, so it is there after every round at no cost to the free tier. Roulette has no decisions to grade. Grading a poker hand is still an open question (section 15).
- **At the poker table, built.** Banca is the opponent there, so the coach is a second agent: built afresh for each question from the asking player's own view, reading the table through the same four tools the opponent uses, and sharing nothing with it. At a shared table its answer goes to the asker alone. Poker has no single right play, so the standard is looser than at blackjack: advice must be open to the player and must not go plainly against the figures (no folding for nothing, no paying far more than a hand is worth, no bluffs), and within that the coach chooses. The interface says as much: a read, not an answer. Grading a poker hand afterwards is still open (section 15).

### 7.8 Social
Shared tables and rooms are built (section 6). Each has its own chat: a row of set phrases and emotes, and free text. The first draft allowed only the phrases, to avoid moderation; free text was added because a room where people cannot talk is not a room. What makes that affordable is that the server tidies every line (one line, 140 characters, links removed, the worst words starred), limits how often anyone speaks, keeps nothing once the server stops, and lets each player mute any other. Private tables by invitation are built (section 6): the player who opens one is shown the link to send, through the phone's share sheet where there is one. A friends list, with who is online, remains a stretch.

### 7.9 Responsible framing and accessibility
A plain statement that chips are play money with no real value and no way to buy them, in the README, the lobby and the privacy page. A privacy page says what is kept and who can see it.

**The session reminder.** The chips are play money but the time is real, so after an hour in one sitting Banca says how long it has been and asks whether it is a good moment for a break. It only mentions it: the card covers no controls, stops nothing, and offers "Keep playing" beside "Leave the table". It comes round again after the same length. The player sets the length on their profile (30 minutes, 1 hour, 2 hours) or turns it off; it is on at an hour by default, because a reminder nobody has switched on reminds nobody. A sitting ends after ten minutes with Banca closed or out of sight, and time in a background tab is not counted. All of it is kept in the browser and nothing about how long anyone plays is sent to the server.

Accessibility, as built:

- **Nothing rests on colour alone.** Red and black at roulette are the one place the game depends on telling two colours apart, and they are the pair colour blindness most often confuses. Red is hatched wherever it appears, on the layout, the wheel and the run of past results, and every box says its colour to a screen reader. Wins and losses carry a sign as well as a colour, and suits are shapes.
- **Everything works from a keyboard.** Every control is a real button with a name. Focus is always ringed. A panel that opens takes focus, keeps Tab inside itself, closes on Escape and hands focus back. The roulette layout, some fifty buttons, is crossed with the arrow keys.
- **Motion is optional.** With the system's reduce-motion setting on, cards, chips, the wheel and counting numbers arrive at once instead of travelling.
- **Screens name themselves**, in the tab title and to a screen reader. Cards are announced by name, and results, advice and notices are spoken as they arrive.

Known limits: the shared tables run on clocks that cannot be extended, so a player who needs longer has the tables for one, which wait for ever. On the narrowest phones the roulette number boxes are 22 points wide, under the 24 recommended, though spaced so that neighbouring targets do not overlap. Contrast and screen-reader behaviour were checked by inspection, not yet with real assistive technology.

### 7.10 Staging

- **Built:** identity, profile and stats, levels and achievements, daily rewards, bust protection, the coach before a decision at all three games, grading after a blackjack round, the tutorial, shared tables with chat, leagues, trophies and public pages.
- **Left for v1 (through Phase 7):** demo video.
- **Later:** unlocks by level, a friends list, daily missions, round replay with the agent's reasoning attached, spectator mode, tournaments.

## 8. Components

Each unit has one purpose and a narrow interface so it can be tested alone. Paths are under `backend/src/main/kotlin/com/banca/`.

### 8.1 Poker engine (`games/poker`)
Pure Kotlin, no I/O, no framework imports. Deck and shuffling with an injectable source of randomness, dealing, hand evaluation, betting rounds, legal actions, side pots, showdown, for any number of seats. State is immutable: applying an action returns a new hand. Tested exhaustively for hand ranking, side pots and betting edge cases, with property tests asserting that chips are conserved.

### 8.2 Blackjack engine (`games/blackjack`)
The same shape. Hit, stand, double, split, insurance. A round can be played alone or as one seat of several against a shared dealer hand. `Strategy` works out what every play is worth from the rules themselves, which is what the coach is checked against.

### 8.3 Roulette engine (`games/roulette`)
Bets, the wheel, and settling a layout against a pocket. `Outlook` settles a layout on all 37 pockets to say what it can do, which is what the analyst reads from.

### 8.4 What the games share
The first draft expected a game-module interface to be extracted in Phase 3. It was not, on purpose. Poker is players against each other with hidden cards and a pot; blackjack is each player against a dealer; roulette has no decisions at all once the bets are down. An interface wide enough for all three would have said almost nothing. What they do share was extracted where it is real:

- the connection handshake, the per-player bankroll and the stake and broke notices (`GameConnection`, `PlayerSession`);
- the register that keeps tables alive across connections (`Tables`);
- the agent's tool-calling loop (`ToolConversation`), used by the opponent, the coach and the analyst alike;
- asking Banca something and getting the answer later (`Consultation`);
- room chat (`RoomChat`), and on the client the shell, socket, pickers and drawers under `frontend/src/casino`.

### 8.5 Tables (`sessions`, `ws`)
Live tables are held in memory. A table for one (`PokerTable`, `BlackjackTable`, and roulette's) belongs to one player; a shared one (`PokerRoom`, `BlackjackRoom`, `RouletteRoom`) seats several and runs on its own clock, with timers for betting windows and turns. Every action, a player's or the agent's, goes through the same engine call.

A table belongs to the server, not to the connection. Dropping and returning within three minutes finds it as it was; opening it elsewhere takes it over; a table nobody returns to is cleared, and a round still in play is finished in the way that risks nothing more and then recorded, so leaving is never a way out of losing.

### 8.6 Players and the ledger (`players`)
One chip balance per player, shared across every game. Every change is an append-only ledger entry with a reason (signup grant, round, daily reward, house stake, league prize), and the balance is their sum. `Rewards` decides what is owed and when; `DashboardBuilder` turns rounds and ledger into the profile; `Leagues` holds the league rules as pure functions and `Leaderboards` settles weeks and answers the boards. The store has two implementations behind one contract test: Postgres, and an in-memory one for running without a database.

### 8.7 Identity (`players`)
A player is known by a bearer token whose hash is all the server keeps. Accounts are held by Supabase Auth; the server confirms a sign-in by asking Supabase whose access token it was given, then issues its own token, so the rest of the system never needs to know how anyone signed in.

### 8.8 Realtime gateway (`ws`)
One WebSocket address per table. The first frame is a `hello` with the player's token. After every change each seat is sent its whole view, never a delta, with nothing in it that seat may not see. The contract is `docs/protocol.md`.

### 8.9 Agent runtime (`agents`)
When Banca has something to decide or is asked something, the runtime hands the model its view and a list of tools, lets it call them until it answers, and enforces a limit on calls to the model and on time. It emits a trace of each step. At poker the trace is shown as it happens with the details held back, since they could give Banca's cards away, and revealed in full when the hand ends; for the coach and analyst, who see only what the player sees, nothing is held back.

It talks to a `ModelProvider`: Ollama, any OpenAI-compatible host (Groq when deployed), a fallback that tries one after another, and a passive one that needs no model at all. Personalities (tight, aggressive, bluffer) remain a stretch.

### 8.10 MCP tools (`agents`)
One small MCP server per role, connected to the runtime over an in-process transport: the same protocol as a separate server, without a second process to host.

- **Poker opponent:** `get_game_state`, `get_legal_actions`, `get_hand_equity` (Monte Carlo, computed locally), `get_pot_odds`, `submit_action`
- **Poker coach:** the opponent's four reading tools, built from the asking player's view, and `give_advice` in place of `submit_action`
- **Blackjack coach:** `get_table_state`, `get_action_values`, `get_odds`, `give_advice`
- **Roulette analyst:** `get_layout`, `get_chances`, `get_cost`, `give_read`

A tool never exposes what its seat is not entitled to. The analyst is given no history of the wheel, so it has nothing from which to say a number is due. Only the opponent can act: a coach has no tool that plays a hand, and what it submits is validated by the engine like anyone's action.

### 8.11 Persistence (`db/migrations`)
Postgres: `profiles`, `player_tokens`, `wallet_entries`, `rounds`, `round_actions`, `round_results`, `league_weeks`, `league_results`. A round is written when it completes, with its result and the ledger entry in one transaction. Live tables are not persisted; a backend restart ends the rounds in play and charges nothing for them.

### 8.12 Frontend (`frontend/src`)
A lobby, a picker and table for each game in both forms, the profile, the leagues and leaderboards, public player pages, and Banca's guide to each game, routed by the URL's hash. A shared **reasoning panel** shows Banca's tool calls and decision in every game. The server sends states, and the client works out what happened between two of them in order to animate it. The client renders and never decides rules.

## 9. Data flow (one agent turn)

1. The table reaches Banca's seat and calls the agent runtime.
2. The runtime starts the model with that seat's view and the tool list for the current game.
3. The model calls tools; each call and result is emitted as a trace event and forwarded to clients.
4. The model calls `submit_action`; the engine validates and applies it.
5. The table sends every seat its new view, and at the end of the hand, Banca's full reasoning.

## 10. Error handling

- Illegal or missing agent action → safest legal fallback (check if free, otherwise fold; stand in blackjack; no bet in roulette). The trace shows the fallback.
- Model failure, timeout, or a rate-limited free tier → same fallback, logged. A table never stalls because a provider is throttling.
- Human disconnect → the table waits. At a table for one the round is as it was left for three minutes; at a shared one the turn timer plays the safe action when it runs out. Reconnecting restores the seat's view either way.
- The same table opened twice → the newer connection takes it over and the older is told it has been replaced.
- Cold start on the free hosting tier → the client shows an explicit "waking up the table" state rather than appearing broken.
- Malformed client message → rejected with an error; never crashes a table.
- Wallet: every debit and credit is one ledger transaction; a failed round cannot leave chips half-moved. Daily rewards are idempotent per day, so a retry cannot pay twice, and a league week is settled exactly once however many requests arrive together.
- A coach or analyst that fails or disagrees with the figures → the figures answer, marked as coming from the book.

## 11. Testing strategy

- Engines: heavy unit and property tests: highest coverage in the project, including chip conservation and payout correctness.
- Agent runtime: a scripted fake model verifies the tool loop, timeout, budget and fallback without network calls.
- MCP tools: tests asserting no hidden information leaks.
- Gateway: integration tests with in-memory WebSocket clients playing a full round.
- Players: ledger tests asserting the balance always equals the sum of entries, and that a reward cannot be claimed twice in one server day. One contract test runs against both stores, the Postgres one included.
- Leagues: the rules as pure functions, and settlement of a week run twice to show it pays once.
- Frontend: unit tests for the pure parts, such as deriving events from two states and the roulette layout. Component tests and an end-to-end smoke test are still to add.
- CI runs everything on every push, against a real Postgres with the migrations applied.

## 12. Roadmap

| Phase | Outcome | Status |
|---|---|---|
| 0. Setup | Monorepo, CI, Ktor skeleton, DB schema, public GitHub repo | Done |
| 1. Poker engine | Fully tested Hold'em engine, no interface | Done |
| 2. Skeleton | One human vs one agent at one poker table: gateway, MCP tools, agent runtime on a free model provider, React table and reasoning panel, deployed | Done |
| 3. Blackjack | Second game, with Banca as coach | Done |
| 4. Roulette | Third game, with Banca as analyst | Done |
| 5. Platform | Lobby, shared wallet, guest identity and Google sign-in, tables that survive reconnects, shared tables and rooms at all three games, chat | Done |
| 6. Player experience | Profile and stats, daily rewards, bust protection, coach at all three games, grading after a blackjack round, tutorial, leagues and trophies | Done |
| 7. Polish and launch | PWA install, accessibility, session reminder, architecture diagram: done. Demo video: to do | In progress |
| 8. Stretch | Private tables by invitation: done. Unlocks by level, a friends list, agent personalities, missions, replay, spectator, tournaments | Open |

Phases are taken in order. The first draft allowed 20 to 27 weeks; Phases 0 to 6 were done well inside that, with nothing cut from Phase 6.

**Success criteria for v1** (end of Phase 7): a public URL where a stranger on a phone is playing within seconds, gets a tutorial, claims a daily bonus, plays real rounds of all three games alone or with others on one chip balance, watches the agent reason, and sees their stats afterwards; CI green; the README explains the architecture in under five minutes.

## 13. Repository layout

```
banca/
├── backend/              Ktor app
│   └── src/main/kotlin/com/banca/
│       ├── games/        poker, blackjack, roulette engines
│       ├── sessions/     tables for one and the views seats are sent
│       ├── ws/           gateway, table register, shared rooms, chat
│       ├── agents/       agent runtime, MCP tools, model providers
│       └── players/      identity, ledger, rewards, stats, leagues
├── db/migrations/        Postgres schema
├── frontend/             React + TS app
├── docs/
│   ├── architecture.svg
│   ├── protocol.md
│   └── superpowers/specs/
├── .github/workflows/    CI and the keep-warm ping
└── README.md
```

## 14. Decisions made along the way

The first draft left three questions open and made some choices the build later changed. Each is recorded here with its reason.

| Question or first choice | What was decided | Why |
|---|---|---|
| MCP server language: Kotlin or TypeScript | Kotlin, on the official SDK, inside the backend process | One toolchain, and the tools call the engines directly. An in-process transport keeps the protocol real without a second service to host for free |
| Which free model provider | Groq, with a list of models tried in order | Rate limits are per model, so falling through to the next keeps a table moving when one is throttled |
| Agent's role at blackjack and roulette | Coach and analyst, never dealer or croupier | A dealer follows fixed rules, so a model adds nothing there and could only get it wrong |
| A shared game-module interface | Not extracted; sharing happens at the table, connection and agent layers | The three engines have too little in common (8.4) |
| JWT on connect, sign-in first | A guest token from the first visit, Google sign-in optional | Nobody should have to make an account to try a card game, and the server's own token keeps the provider out of everything else |
| Separate `wallet` and `progression` packages, `streaks` and `achievements` tables | One `players` package; streaks, levels and achievements computed from the ledger and rounds | A number that is derived cannot drift from the history it describes |
| Daily ladder up to 15,000; top-up to 2,000 below a floor | 200 to 2,000 across a week; a stake of 500 when a round cannot be covered | Sized against a 2,000 bankroll and 500 limits, so chips stay worth winning |
| Quick phrases only in chat | Phrases and free text | A room where people cannot talk is not a room. Tidying, rate limits, muting and keeping nothing make it affordable (7.8) |
| Weekly and all-time leaderboards | Those, plus weekly leagues with promotion, prizes and trophies, for signed-in players | A reason to come back each week, and a reason to sign in |
| Levels and achievements in v1.1 | Built with the profile | The profile was thin without them, and both fall out of the rounds already recorded |
| Framer Motion, DiceBear, CC0 audio, Google Fonts | CSS and Web Animations, initials, synthesised sound, system fonts | Fewer dependencies and nothing fetched from a third party |
| "Private table" meant a table for one | A private table is one a player opens and invites friends to; the table for one is "Play alone" | That is what the words mean to a player, and it was the first thing asked for once friends were to be invited |
| A separate `mcp-servers/` directory | Tools live in `backend/.../agents` | They are part of the one process (see the first row) |

## 15. Open questions

- **Grading a poker hand.** Blackjack is graded exactly from the figures (7.7). Poker has no single right play, so its grading needs a standard that is fair without pretending to be exact. The bounds the poker coach is held to are a start: a decision could be marked only when it falls outside them.
- **What levels unlock.** Higher-stake tables are the obvious answer, once there are enough players for more tables to be worth having.
