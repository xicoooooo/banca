# AI Poker Table — Design Spec

Date: 2026-09-27
Author: Francisco Aragão Dias

## 1. Purpose

A real-time multiplayer Texas Hold'em platform where humans play against each other and against AI agents. The AI agents decide by calling tools exposed over the Model Context Protocol (MCP), and their reasoning (tool calls and decisions) is streamed live to the UI.

Goals, in priority order:

1. Real users: friends can open a link and play within minutes.
2. Recruiter signal: a deployed demo plus a clean, tested, documented monorepo showing real-time backend engineering and applied agent/tool-use architecture.
3. Learning: deepen Kotlin/Ktor concurrency, WebSockets, MCP, and deployment skills.

Non-goals: real-money play, monetization, native mobile apps, tournament infrastructure (stretch only).

Timeline: 4–8 months, solo, part-time alongside the MSc.

## 2. Guiding decisions

- **Game:** Texas Hold'em No-Limit, 2–6 seats. Chosen over another dice game so it is clearly distinct from the academic Chelas Poker Dice project and gives the AI real decisions to reason about (equity, pot odds, opponent history).
- **Walking skeleton first:** the AI agent is the differentiator, so it is built *before* the multiplayer lobby. The first milestone is one human vs one AI agent on a single table, end to end, with a rough UI.
- **Monorepo:** `backend/`, `frontend/`, `mcp-servers/`, `docs/` in one repository.
- **YAGNI:** no microservices, no message broker, no multi-table scaling work until a real need appears. One backend process holds all live tables in memory; Postgres stores durable data.

## 3. Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Kotlin + Ktor | Coroutines suit the game loop and agent calls; already used in a prior project |
| Real-time | WebSockets (Ktor) | Players send actions (bet/call/raise/fold), not just receive |
| Database | PostgreSQL (Supabase) | Users, hand history, stats |
| Auth | Supabase Auth, JWT verified in Ktor | Known pairing, avoids hand-rolled auth |
| AI | Claude via Anthropic API (Bedrock as alternative) | Tool-calling agent |
| MCP | Kotlin or TypeScript MCP server exposing poker tools | Demonstrates MCP knowledge with something real |
| Frontend | React + TypeScript + Vite + Tailwind + Framer Motion | Known stack |
| Deploy | Vercel (frontend), Fly.io or Render (backend), Supabase (DB) | Free-tier friendly |
| CI | GitHub Actions: backend tests, frontend build/lint | Production-shaped repo |

## 4. Components

Each unit has one purpose and a narrow interface so it can be tested alone.

### 4.1 Game engine (`backend/.../engine`)
Pure Kotlin, no I/O, no framework imports.
- Responsibilities: deck and shuffling (injectable RNG for deterministic tests), dealing, hand evaluation, betting rounds, legal-action computation, side pots, showdown.
- Interface: `Table.apply(action): Result<TableState>` and `Table.legalActions(seat)`. State is immutable; each action returns a new state plus events.
- Depends on: nothing.
- Testing: exhaustive unit tests for hand ranking, side pots, and betting edge cases; property-style tests for chip conservation (total chips never change).

### 4.2 Table service (`backend/.../tables`)
- Responsibilities: owns live tables in memory, seat assignment, turn timers, applying actions from any actor (human or agent) through one code path, publishing events.
- Interface: `join`, `leave`, `act`, event stream per table.
- Depends on: game engine.

### 4.3 Realtime gateway (`backend/.../ws`)
- Responsibilities: WebSocket sessions, JWT auth on connect, translating client messages to table service calls, broadcasting per-seat views of state (a player never receives other players' hole cards).
- Contract: a versioned JSON message schema documented in `docs/protocol.md`.

### 4.4 Agent runtime (`backend/.../agents`)
- Responsibilities: when it is an AI seat's turn, run the agent loop: give the model the seat's view of the game, let it call tools until it submits an action, enforce a timeout and a safe fallback (check/fold) if the model fails or returns an illegal action.
- Emits a reasoning trace event (tool calls, arguments, results, final action) that flows to the UI.
- Personalities are prompt configurations (e.g. tight, aggressive, bluffer), added after the base agent works.

### 4.5 MCP poker tools (`mcp-servers/poker`)
Tools the agent calls, each read-only except `submit_action`:
- `get_game_state` — the seat's own view (hole cards, board, pot, stacks, positions)
- `get_legal_actions`
- `get_hand_equity` — Monte Carlo equity vs random or ranged opponents
- `get_pot_odds`
- `get_action_history` — actions this hand and per-opponent tendencies
- `submit_action`

The tools never expose hidden information the seat is not entitled to. Illegal actions are rejected by the engine, not trusted from the model.

### 4.6 Persistence
Postgres tables: `profiles`, `hands`, `hand_actions`, `hand_results`. Written when a hand completes. Live table state is not persisted in v1; a backend restart ends active hands (acceptable, documented).

### 4.7 Frontend
- Screens: sign-in, lobby, table, stats/leaderboard.
- Table view: seats, cards, pot, action controls, animations.
- **Reasoning panel:** live feed of the agent's tool calls and final decision for the current hand.
- State comes from the WebSocket; the client renders and never decides game rules.

## 5. Data flow (one AI turn)

1. Table service reaches an AI seat and notifies the agent runtime.
2. Agent runtime starts the model with the seat's view and the MCP tool list.
3. Model calls tools (state, legal actions, equity, pot odds, history).
4. Each call and result is emitted as a trace event; the gateway forwards it to clients.
5. Model calls `submit_action`; the engine validates and applies it.
6. Table service broadcasts the new state to all seats.

## 6. Error handling

- Illegal or missing agent action → fallback to check if free, otherwise fold; the trace shows the fallback.
- Model or API failure/timeouts → same fallback, logged.
- Human disconnect → turn timer expires, auto check/fold; reconnect within the session restores the seat view.
- Malformed client messages → rejected with an error message; never crash the table.

## 7. Testing strategy

- Engine: heavy unit and property tests (highest coverage).
- Agent runtime: tests with a scripted fake model to verify tool loop, timeout, and fallback behavior without network calls.
- MCP tools: tests that no hidden information leaks.
- Gateway: integration tests with in-memory WebSocket clients playing a full hand.
- Frontend: component tests for the table and action controls; one end-to-end smoke test on a deployed or local stack.
- CI runs all of the above on every push.

## 8. Roadmap (walking skeleton first)

| Phase | Time | Outcome |
|---|---|---|
| 0. Setup | ~2 wks | Monorepo, CI, Ktor skeleton, DB schema, GitHub repo |
| 1. Engine | 3–4 wks | Fully tested hold'em engine, no UI |
| 2. Skeleton | 4–5 wks | One human vs one AI agent on one table: WebSocket gateway, MCP tools, agent runtime, minimal React table and reasoning panel, deployed |
| 3. Multiplayer | 3–4 wks | Lobby, 2–6 seats, humans plus AIs, auth, reconnects |
| 4. Polish | 2–3 wks | Animations, stats/leaderboard, README, architecture diagram, demo video |
| 5. Stretch | open | AI personalities, spectator mode, tournaments, voice table-talk |

Success criteria for v1 (end of Phase 4): a public URL where a stranger can sign in, sit at a table with an AI, play full hands, and watch the AI's reasoning; CI green; README explains the architecture in under five minutes.

## 9. Repository layout

```
ai-poker-table/
├── backend/          Ktor app: engine, tables, ws, agents
├── mcp-servers/
│   └── poker/        MCP tool server
├── frontend/         React + TS app
├── docs/
│   ├── protocol.md
│   └── superpowers/specs/
├── .github/workflows/
└── README.md
```

## 10. Open questions

- MCP server language: Kotlin (single toolchain, shares the engine) vs TypeScript (official SDK maturity). Decide at the start of Phase 2 with a short spike; default to Kotlin if the official Kotlin SDK covers the needed features.
- Anthropic API vs Bedrock for the agent: default to the Anthropic API for simplicity; revisit if credits or cost dictate.
