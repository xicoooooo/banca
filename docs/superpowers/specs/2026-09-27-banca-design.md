# Banca — Design Spec

Date: 27/09/2026
Author: Francisco Aragão Dias

## 1. Purpose

Banca is a multi-game social casino played with virtual chips. One account, one chip balance, several table games: Texas Hold'em poker, blackjack, and roulette. Each game can be played against other people or against agents driven by a large language model, and those agents decide by calling tools over the Model Context Protocol (MCP). Their reasoning is streamed to the interface as it happens, so a player can watch the agent work out its decision.

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

- **Poker first, then extract.** Hold'em is built first, alone. The shared game-module interface is extracted when blackjack is added and has a second real implementation to answer to, not designed up front against one game. Roulette then tests whether the abstraction holds.
- **Walking skeleton before breadth.** The first milestone is one human against one agent at one poker table, end to end, with a rough interface. The agent is the hard part and the differentiator, so it exists early even if ugly.
- **One process, in memory.** Live tables live in the backend process; Postgres stores durable data. No message broker, no microservices, no horizontal scaling work until something actually demands it.
- **The model is pluggable.** The agent runtime talks to a `ModelProvider` interface, not to a vendor. Free providers are the default; a paid one can be switched on later by configuration alone.
- **Monorepo:** `backend/`, `frontend/`, `mcp-servers/`, `docs/`.

## 4. Target platforms

One codebase: a responsive web application, installable as a PWA.

- **Phones (iOS and Android):** the PWA installs to the home screen and runs full screen. No app-store build, because an Apple Developer account costs 99 EUR/year and Google Play charges a 25 EUR registration fee, and neither is worth paying now.
- **Tablets and desktop browsers:** the same application, wider layout.
- **Offline:** the shell loads offline and shows a clear reconnect state, but playing requires a connection, since the server owns the rules.

The interface is designed phone-first, since that is how a social casino is actually used.

## 5. Stack and cost control

Every entry is free at this project's scale. The right-hand column records the limit that matters and what happens when it bites.

| Layer | Choice | Free-tier reality |
|---|---|---|
| Backend | Kotlin + Ktor | Self-hosted code, no cost |
| Real-time | WebSockets (Ktor) | No cost |
| Backend hosting | Render free web service | No card needed. Spins down after ~15 min idle, cold start around 50 s. A scheduled ping from GitHub Actions keeps it warm inside the free instance-hour allowance |
| Database + auth | Supabase free tier | 500 MB, and projects pause after 7 days of inactivity. The same scheduled job touches the database weekly to prevent the pause |
| Frontend hosting | Vercel Hobby | Free for non-commercial personal projects; this qualifies |
| Domain | none, use `*.vercel.app` | Paid domains are deferred. `banca.gg` and `getbanca.com` were free to register if that ever changes |
| Agent model | Ollama locally in development; Groq or Google AI Studio free tier for the deployed demo | Both hosted tiers are free without a card and support tool calling. Rate limits are low, which the agent budget in 6.8 is designed around |
| MCP | Self-written MCP server | No cost |
| Frontend | React + TypeScript + Vite + Tailwind + Framer Motion | No cost |
| CI | GitHub Actions on a public repository | Unlimited minutes for public repos, which is one reason the repository is public |
| Avatars | Generated (DiceBear, MIT) from the account id | No uploads, so no storage bill and no moderation burden |
| Sounds and fonts | CC0 audio, Google Fonts | No cost |

**On the model provider:** Claude via the Anthropic API or Bedrock is not free, so it is not the default. This costs the project nothing architecturally, because MCP is an open protocol and tool calling is supported by the free providers above. The `ModelProvider` interface keeps Anthropic as a one-line switch if credits ever appear, and the README can honestly say the agent runs against any tool-calling model.

## 6. The three games

They differ in shape, which is exactly why the platform is interesting to build:

| Game | Shape | Opponent | Agent's role |
|---|---|---|---|
| Texas Hold'em | Turn-based, players against each other, hidden cards | Other seats | Opponent |
| Blackjack | Turn-based, each player against the dealer, dealer follows fixed rules | The house | Dealer, or a coach advising the player |
| Roulette | Simultaneous betting, one spin resolves everything, many bets per player | The house | Croupier, or a coach explaining odds |

Roulette bet types in scope: single number, red/black, odd/even, high/low, dozens, columns, splits. Payouts follow European single-zero rules.

## 7. Player experience

What makes it feel like one app rather than three demos.

### 7.1 Profile and stats
Avatar, display name, level, and the numbers that make a player want to play again: chips held, lifetime hands or spins per game, win rate, biggest pot won, longest streak, blackjack decisions matching basic strategy, most-played roulette bet. Achievements appear here once they exist. A chip-balance chart over time comes from the wallet ledger, so it costs nothing extra to build.

### 7.2 Daily login rewards
A streak that escalates and resets on a missed day:

| Day | Chips |
|---|---|
| 1 | 200 |
| 2 | 1,000 |
| 3 | 2,000 |
| 4 | 3,500 |
| 5 | 5,000 |
| 6 | 7,500 |
| 7 | 15,000 |

After day 7 the cycle repeats from day 1, and the profile keeps the longest streak reached. Awarded against server time in the player's timezone, never the device clock, and recorded as a wallet ledger entry like any other chip movement.

### 7.3 Bust protection
Chips cannot be bought, so no player may ever be stuck at zero. Below a floor of 500 chips, a free top-up to 2,000 is available, with a cooldown of a few hours. It is framed as the house staking you, which fits the name.

### 7.4 Onboarding tutorial
First run offers a short guided round of the chosen game: a poker hand with prompts explaining position, pot and actions; a blackjack hand explaining hit, stand and double; a roulette round explaining the bet types on the felt. Skippable, and replayable later from the profile. The coach agent (7.6) narrates it, which means the tutorial reuses the agent work instead of being a separate system.

### 7.5 Levels and unlocks
XP for every round played, more for rounds won. Levels gate higher-stake tables and extra agent personalities, which gives progression a purpose beyond a number.

### 7.6 Coach mode
A toggle at any table. Before acting, the player can ask the coach for a read; after the round, the coach grades the decision against what the tools said. This is the feature that separates Banca from an ordinary social casino: the same agent that plays against you can teach you, and its reasoning is visible either way. It is also the strongest thing to show a recruiter.

### 7.7 Social
Friends list, and private tables opened by invite link so a group can play together. Table chat is a fixed set of quick phrases and emotes rather than free text, which removes moderation, abuse and storage concerns at no cost to the fun.

### 7.8 Leaderboards
Weekly and all-time chip leaders per game, from data the wallet ledger already holds.

### 7.9 Responsible framing and accessibility
A plain statement that chips are play money with no real value and no way to buy them, visible at sign-up and in the profile, plus an optional session-length reminder. Roulette distinguishes red and black by pattern as well as colour for colourblind players; the interface is keyboard navigable and respects reduced-motion settings.

### 7.10 Staging
Not all of this lands at once.

- **v1 (through Phase 7):** profile and stats, daily rewards, bust protection, onboarding tutorial, coach mode, leaderboards, responsible framing, accessibility, PWA install.
- **v1.1:** levels and unlocks, friends and private tables, quick chat, achievements.
- **Later:** daily missions, round replay with the agent's reasoning attached, spectator mode, tournaments.

## 8. Components

Each unit has one purpose and a narrow interface so it can be tested alone.

### 8.1 Poker engine (`backend/.../games/poker`)
Pure Kotlin, no I/O, no framework imports.
- Deck and shuffling (injectable RNG for deterministic tests), dealing, hand evaluation, betting rounds, legal-action computation, side pots, showdown.
- Interface: `apply(state, action): Result<state, events>` and `legalActions(state, seat)`. State is immutable.
- Testing: exhaustive unit tests for hand ranking, side pots, betting edge cases; property tests asserting chips are conserved.

### 8.2 Blackjack engine (`backend/.../games/blackjack`)
Same shape. Hit, stand, double, split, insurance; configurable deck count and dealer-stands-on rule.

### 8.3 Roulette engine (`backend/.../games/roulette`)
Same shape, but resolution is simultaneous: collect bets during a window, spin once, pay out every bet against the result. European single zero.

### 8.4 Game module interface (`backend/.../games`)
**Extracted during Phase 3, not written in Phase 1.** Expected to converge on roughly: a game declares its phases, the legal actions per actor in the current phase, how to apply an action, how to resolve a round, and what each actor is allowed to see. The session service and gateway then work against that interface instead of against poker specifically. If blackjack and roulette do not fit cleanly, the interface is wrong and gets reshaped, that judgment needs two real implementations to make.

### 8.5 Session service (`backend/.../sessions`)
Owns live tables in memory regardless of game: seat assignment, turn timers, applying actions from any actor, human or agent, through one code path, publishing events. Depends on the game modules, not on any specific game.

### 8.6 Wallet (`backend/.../wallet`)
One chip balance per account, shared across every game. Every change is an append-only ledger entry referencing what caused it, whether a round, a daily reward or a top-up, so a balance can always be explained and the profile charts come free.

### 8.7 Progression (`backend/.../progression`)
Daily streaks, XP and levels, achievements, and the leaderboard queries. Kept apart from the wallet: progression decides that a reward is owed, the wallet records the chips moving.

### 8.8 Realtime gateway (`backend/.../ws`)
WebSocket sessions, JWT auth on connect, translating client messages into session-service calls, broadcasting a per-actor view of state so a player never receives another player's hidden cards. Contract: a versioned JSON message schema documented in `docs/protocol.md`.

### 8.9 Agent runtime (`backend/.../agents`)
When it is an agent's turn, run the loop: hand the model that seat's view, let it call tools until it submits an action, enforce a timeout and a safe fallback if the model fails or returns something illegal. Emits a reasoning trace (tool calls, arguments, results, final action) that flows to the interface. Personalities: tight, aggressive, bluffer, are prompt configurations added once the base agent works.

Talks to a `ModelProvider` interface with implementations for Ollama, Groq and Google AI Studio, and optionally Anthropic. Because the free tiers are rate limited, each turn has a budget: a capped number of tool calls, a timeout, and cached equity results within a hand. Exceeding the budget falls back to the safe action rather than queuing.

### 8.10 MCP tools (`mcp-servers/`)
Shared tools: `get_game_state`, `get_legal_actions`, `get_action_history`, `submit_action`.

Per-game tools:
- Poker: `get_hand_equity` (local Monte Carlo, no API), `get_pot_odds`
- Blackjack: `get_basic_strategy`, `get_running_count` (only if the variant allows it, and disclosed)
- Roulette: `get_bet_odds`, `get_payout_table`

Read-only except `submit_action`. Tools never expose information the actor is not entitled to. Illegal actions are rejected by the engine, never trusted from the model. Coach mode calls the same tools, which is why it costs so little to add.

### 8.11 Persistence
Postgres: `profiles`, `wallet_entries`, `rounds`, `round_actions`, `round_results`, `streaks`, `achievements`. Written when a round completes. Live state is not persisted in v1; a backend restart ends active rounds, which is acceptable and documented.

### 8.12 Frontend
Screens: sign-in, lobby (pick a game and a table), the three table views, profile and stats, leaderboards. A shared **reasoning panel** shows the agent's tool calls and decision live, in every game. The client renders and never decides rules.

## 9. Data flow (one agent turn)

1. The session service reaches an agent seat and notifies the agent runtime.
2. The runtime starts the model with that seat's view and the tool list for the current game.
3. The model calls tools; each call and result is emitted as a trace event and forwarded to clients.
4. The model calls `submit_action`; the engine validates and applies it.
5. The session service broadcasts the new state to every seat.

## 10. Error handling

- Illegal or missing agent action → safest legal fallback (check if free, otherwise fold; stand in blackjack; no bet in roulette). The trace shows the fallback.
- Model failure, timeout, or a rate-limited free tier → same fallback, logged. A table never stalls because a provider is throttling.
- Human disconnect → turn timer expires and auto-plays the safe action; reconnecting restores the seat view.
- Cold start on the free hosting tier → the client shows an explicit "waking up the table" state rather than appearing broken.
- Malformed client message → rejected with an error; never crashes a table.
- Wallet: every debit and credit is one ledger transaction; a failed round cannot leave chips half-moved. Daily rewards are idempotent per day, so a retry cannot pay twice.

## 11. Testing strategy

- Engines: heavy unit and property tests: highest coverage in the project, including chip conservation and payout correctness.
- Agent runtime: a scripted fake model verifies the tool loop, timeout, budget and fallback without network calls.
- MCP tools: tests asserting no hidden information leaks.
- Gateway: integration tests with in-memory WebSocket clients playing a full round.
- Wallet and progression: ledger tests asserting the balance always equals the sum of entries, and that a streak cannot be claimed twice in one server day.
- Frontend: component tests for table views and action controls; one end-to-end smoke test.
- CI runs everything on every push.

## 12. Roadmap

| Phase | Time | Outcome |
|---|---|---|
| 0. Setup | ~2 wks | Monorepo, CI, Ktor skeleton, DB schema, public GitHub repo |
| 1. Poker engine | 3-4 wks | Fully tested Hold'em engine, no interface |
| 2. Skeleton | 4-5 wks | One human vs one agent at one poker table: gateway, MCP tools, agent runtime on a free model provider, minimal React table and reasoning panel, deployed |
| 3. Blackjack | 2-3 wks | Second game, and the game-module interface extracted from two real implementations |
| 4. Roulette | 1-2 wks | Third game, testing the interface against a simultaneous-bet shape |
| 5. Platform | 3-4 wks | Lobby, multi-seat tables, shared wallet, auth, reconnects |
| 6. Player experience | 3-4 wks | Profile and stats, daily rewards, bust protection, tutorial, coach mode, leaderboards |
| 7. Polish and launch | 2-3 wks | PWA install, animations, accessibility, responsible framing, README, architecture diagram, demo video |
| 8. Stretch | open | Levels, friends and private tables, achievements, missions, replay, spectator, tournaments |

Roughly 20-27 weeks, about 5 to 6.5 months. The player-experience work consumed most of the slack that existed before, so if the schedule slips, Phase 6 is cut down rather than Phase 2: a smaller feature set around a working agent beats a rich shell around a weak one.

**Success criteria for v1** (end of Phase 7): a public URL where a stranger signs in on a phone, gets a tutorial, claims a daily bonus, plays real rounds of all three games against agents with a shared chip balance, watches the agent reason, and sees their stats afterwards; CI green; the README explains the architecture in under five minutes.

## 13. Repository layout

```
banca/
├── backend/              Ktor app
│   └── src/main/kotlin/
│       ├── games/        poker, blackjack, roulette engines + shared interface
│       ├── sessions/     live tables
│       ├── wallet/       chip ledger
│       ├── progression/  streaks, XP, achievements, leaderboards
│       ├── ws/           realtime gateway
│       └── agents/       agent runtime + model providers
├── mcp-servers/          MCP tool servers
├── frontend/             React + TS app (PWA)
├── docs/
│   ├── protocol.md
│   └── superpowers/specs/
├── .github/workflows/
└── README.md
```

## 14. Open questions

- **MCP server language:** Kotlin (one toolchain, shares engine code) or TypeScript (more mature official SDK). Decide with a short spike at the start of Phase 2; default to Kotlin if its SDK covers what is needed.
- **Which free model provider for the deployed demo:** Groq and Google AI Studio both qualify. Decide in Phase 2 by testing which follows tool-calling instructions more reliably on a poker turn, and keep the other as a fallback provider.
- **Agent's role in blackjack and roulette:** dealer/croupier, or purely a coach. The coach is more useful to watch and more interesting to build; decide at Phase 3 once poker's agent is real.
