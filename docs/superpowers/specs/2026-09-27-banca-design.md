# Banca — Design Spec

Date: 2026-09-27
Author: Francisco Aragão Dias

## 1. Purpose

Banca is a multi-game social casino played with virtual chips. One account, one chip balance, several table games: Texas Hold'em poker, blackjack, and roulette. Each game can be played against other people or against agents driven by a large language model, and those agents decide by calling tools over the Model Context Protocol (MCP). Their reasoning is streamed to the interface as it happens, so a player can watch the agent work out its decision.

The name is the Portuguese and Italian word for the house — the bank that runs the table.

**Goals, in priority order:**

1. Real users: friends can open a link and play within minutes.
2. Recruiter signal: a deployed demo plus a clean, tested, documented monorepo showing real-time backend engineering, extensible game architecture, and applied agent/tool-use design.
3. Learning: deepen Kotlin/Ktor concurrency, WebSockets, MCP, and deployment skills.

**Non-goals:** real-money play of any kind, purchases of chips, native mobile apps, tournament infrastructure (stretch only), and any attempt to be a complete casino. Three games, done well, is the target.

**Timeline:** 4–8 months, solo, part-time alongside the MSc.

## 2. Hard rules

- **Virtual chips only.** Chips are granted free, cannot be bought, cannot be cashed out, and carry no value. This is a product rule, not a detail: it keeps the project clear of gambling regulation and app-store restrictions, and it is stated plainly in the README and the interface.
- **The server owns the rules.** Clients render; they never decide outcomes. Every action — human or agent — is validated by the server.
- **Agents get no hidden information.** An agent sees exactly what a player in its seat would see. This is enforced in the tool layer and covered by tests.

## 3. Guiding decisions

- **Poker first, then extract.** Hold'em is built first, alone. The shared game-module interface is extracted when blackjack is added and has a second real implementation to answer to, not designed up front against one game. Roulette then tests whether the abstraction holds.
- **Walking skeleton before breadth.** The first milestone is one human against one agent at one poker table, end to end, with a rough interface. The agent is the hard part and the differentiator, so it exists early even if ugly.
- **One process, in memory.** Live tables live in the backend process; Postgres stores durable data. No message broker, no microservices, no horizontal scaling work until something actually demands it.
- **Monorepo:** `backend/`, `frontend/`, `mcp-servers/`, `docs/`.

## 4. The three games

They differ in shape, which is exactly why the platform is interesting to build:

| Game | Shape | Opponent | Agent's role |
|---|---|---|---|
| Texas Hold'em | Turn-based, players against each other, hidden cards | Other seats | Opponent |
| Blackjack | Turn-based, each player against the dealer, dealer follows fixed rules | The house | Dealer, or a coach advising the player |
| Roulette | Simultaneous betting, one spin resolves everything, many bets per player | The house | Croupier, or a coach explaining odds |

Roulette bet types in scope: single number, red/black, odd/even, high/low, dozens, columns, splits. Payouts follow European single-zero rules.

## 5. Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Kotlin + Ktor | Coroutines suit the game loop and agent calls; already used in a prior project |
| Real-time | WebSockets (Ktor) | Players send actions, not just receive them |
| Database | PostgreSQL (Supabase) | Accounts, wallet, game history, stats |
| Auth | Supabase Auth, JWT verified in Ktor | Known pairing, avoids hand-rolled auth |
| Agent | Claude via Anthropic API (Bedrock as alternative) | Tool-calling agent |
| MCP | MCP server exposing per-game tools | Demonstrates MCP with something real |
| Frontend | React + TypeScript + Vite + Tailwind + Framer Motion | Known stack |
| Deploy | Vercel (frontend), Fly.io or Render (backend), Supabase (DB) | Free-tier friendly |
| CI | GitHub Actions: backend tests, frontend build and lint | Production-shaped repo |

## 6. Components

Each unit has one purpose and a narrow interface so it can be tested alone.

### 6.1 Poker engine (`backend/.../games/poker`)
Pure Kotlin, no I/O, no framework imports.
- Deck and shuffling (injectable RNG for deterministic tests), dealing, hand evaluation, betting rounds, legal-action computation, side pots, showdown.
- Interface: `apply(state, action): Result<state, events>` and `legalActions(state, seat)`. State is immutable.
- Testing: exhaustive unit tests for hand ranking, side pots, betting edge cases; property tests asserting chips are conserved.

### 6.2 Blackjack engine (`backend/.../games/blackjack`)
Same shape. Hit, stand, double, split, insurance; configurable deck count and dealer-stands-on rule.

### 6.3 Roulette engine (`backend/.../games/roulette`)
Same shape, but resolution is simultaneous: collect bets during a window, spin once, pay out every bet against the result. European single zero.

### 6.4 Game module interface (`backend/.../games`)
**Extracted during Phase 3, not written in Phase 1.** Expected to converge on roughly: a game declares its phases, the legal actions per actor in the current phase, how to apply an action, how to resolve a round, and what each actor is allowed to see. The session service and gateway then work against that interface instead of against poker specifically. If blackjack and roulette do not fit cleanly, the interface is wrong and gets reshaped — that judgment needs two real implementations to make.

### 6.5 Session service (`backend/.../sessions`)
Owns live tables in memory regardless of game: seat assignment, turn timers, applying actions from any actor — human or agent — through one code path, publishing events. Depends on the game modules, not on any specific game.

### 6.6 Wallet (`backend/.../wallet`)
One chip balance per account, shared across every game. Every change is an append-only ledger entry referencing the round that caused it, so a balance can always be explained. Handles free top-ups when a player busts out.

### 6.7 Realtime gateway (`backend/.../ws`)
WebSocket sessions, JWT auth on connect, translating client messages into session-service calls, broadcasting a per-actor view of state so a player never receives another player's hidden cards. Contract: a versioned JSON message schema documented in `docs/protocol.md`.

### 6.8 Agent runtime (`backend/.../agents`)
When it is an agent's turn, run the loop: hand the model that seat's view, let it call tools until it submits an action, enforce a timeout and a safe fallback if the model fails or returns something illegal. Emits a reasoning trace (tool calls, arguments, results, final action) that flows to the interface. Personalities — tight, aggressive, bluffer — are prompt configurations added once the base agent works.

### 6.9 MCP tools (`mcp-servers/`)
Shared tools: `get_game_state`, `get_legal_actions`, `get_action_history`, `submit_action`.

Per-game tools:
- Poker: `get_hand_equity` (Monte Carlo), `get_pot_odds`
- Blackjack: `get_basic_strategy`, `get_running_count` (only if the variant allows it, and disclosed)
- Roulette: `get_bet_odds`, `get_payout_table`

Read-only except `submit_action`. Tools never expose information the actor is not entitled to. Illegal actions are rejected by the engine, never trusted from the model.

### 6.10 Persistence
Postgres: `profiles`, `wallet_entries`, `rounds`, `round_actions`, `round_results`. Written when a round completes. Live state is not persisted in v1; a backend restart ends active rounds, which is acceptable and documented.

### 6.11 Frontend
Screens: sign-in, lobby (pick a game and a table), the three table views, wallet/stats. A shared **reasoning panel** shows the agent's tool calls and decision live, in every game. The client renders and never decides rules.

## 7. Data flow (one agent turn)

1. The session service reaches an agent seat and notifies the agent runtime.
2. The runtime starts the model with that seat's view and the tool list for the current game.
3. The model calls tools; each call and result is emitted as a trace event and forwarded to clients.
4. The model calls `submit_action`; the engine validates and applies it.
5. The session service broadcasts the new state to every seat.

## 8. Error handling

- Illegal or missing agent action → safest legal fallback (check if free, otherwise fold; stand in blackjack; no bet in roulette). The trace shows the fallback.
- Model or API failure or timeout → same fallback, logged.
- Human disconnect → turn timer expires and auto-plays the safe action; reconnecting restores the seat view.
- Malformed client message → rejected with an error; never crashes a table.
- Wallet: every debit and credit is one ledger transaction; a failed round cannot leave chips half-moved.

## 9. Testing strategy

- Engines: heavy unit and property tests — highest coverage in the project, including chip conservation and payout correctness.
- Agent runtime: a scripted fake model verifies the tool loop, timeout, and fallback without network calls.
- MCP tools: tests asserting no hidden information leaks.
- Gateway: integration tests with in-memory WebSocket clients playing a full round.
- Wallet: ledger tests asserting the balance always equals the sum of entries.
- Frontend: component tests for table views and action controls; one end-to-end smoke test.
- CI runs everything on every push.

## 10. Roadmap

| Phase | Time | Outcome |
|---|---|---|
| 0. Setup | ~2 wks | Monorepo, CI, Ktor skeleton, DB schema, GitHub repo |
| 1. Poker engine | 3–4 wks | Fully tested Hold'em engine, no interface |
| 2. Skeleton | 4–5 wks | One human vs one agent at one poker table: gateway, MCP tools, agent runtime, minimal React table and reasoning panel, deployed |
| 3. Blackjack | 2–3 wks | Second game, and the game-module interface extracted from two real implementations |
| 4. Roulette | 1–2 wks | Third game, testing the interface against a simultaneous-bet shape |
| 5. Platform | 3–4 wks | Lobby, multi-seat tables, shared wallet, auth, reconnects |
| 6. Polish | 2–3 wks | Animations, stats, README, architecture diagram, demo video |
| 7. Stretch | open | Agent personalities, spectator mode, tournaments, voice table-talk |

Roughly 17–23 weeks, leaving slack inside the 6–8 month window.

**Success criteria for v1** (end of Phase 6): a public URL where a stranger signs in, picks any of the three games, plays real rounds against agents with a shared chip balance, and watches the agent reason; CI green; the README explains the architecture in under five minutes.

## 11. Repository layout

```
banca/
├── backend/              Ktor app
│   └── src/main/kotlin/
│       ├── games/        poker, blackjack, roulette engines + shared interface
│       ├── sessions/     live tables
│       ├── wallet/       chip ledger
│       ├── ws/           realtime gateway
│       └── agents/       agent runtime
├── mcp-servers/          MCP tool servers
├── frontend/             React + TS app
├── docs/
│   ├── protocol.md
│   └── superpowers/specs/
├── .github/workflows/
└── README.md
```

## 12. Open questions

- **MCP server language:** Kotlin (one toolchain, shares engine code) or TypeScript (more mature official SDK). Decide with a short spike at the start of Phase 2; default to Kotlin if its SDK covers what is needed.
- **Anthropic API or Bedrock:** default to the Anthropic API for simplicity; revisit on cost.
- **Agent's role in blackjack and roulette:** dealer/croupier, or a coach advising the human. The coach is more useful to watch and more interesting to build; decide at Phase 3 once poker's agent is real.
