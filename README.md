<p align="center">
  <img src="frontend/public/logo.png" alt="Banca logo" width="160" />
</p>

# Banca

A multi-game social casino played with virtual chips: Texas Hold'em, blackjack and roulette, with opponents and coaches driven by a language model that decides by calling tools over the [Model Context Protocol](https://modelcontextprotocol.io). Their reasoning streams to the table as it happens, so you can watch an opponent work out its decision, or ask it to explain yours.

> **Play money only.** Chips are free, cannot be bought, cannot be cashed out, and have no value. Banca is not a gambling application and never handles real money.

**Status:** in development, and playable. Heads-up Hold'em runs against an agent that decides by calling poker tools over MCP, with its reasoning shown step by step and revealed in full after each hand. Blackjack is playable against the house. Chips, history and statistics belong to the player and follow them from table to table, with a profile page built from the rounds they have really played. The blackjack coach and roulette come next.

## Why it exists

Three games with three different shapes, which is what makes the architecture worth building:

| Game | Shape | The agent's role |
|---|---|---|
| Texas Hold'em | Turn-based, players against each other, hidden cards | Opponent |
| Blackjack | Turn-based, players against a fixed-rule dealer | Dealer, or coach |
| Roulette | Simultaneous betting, one spin resolves everything | Croupier, or coach |

The agent is not a chatbot bolted to a game. It receives only what a player in its seat can see, calls tools to work out equity, pot odds and legal actions, and submits an action the server validates like any other. It runs against any tool-calling model: Ollama locally, or a free hosted tier.

## Architecture

```
backend/        Kotlin + Ktor
  games/        poker, blackjack, roulette engines + shared interface
  sessions/     live tables
  players/      identity, the chip ledger, and the statistics drawn from it
  ws/           WebSocket gateway
  agents/       agent runtime + pluggable model providers
mcp-servers/    MCP tool servers
frontend/       React + TypeScript, installable as a PWA
```

Game rules live in pure, framework-free modules with immutable state, so they can be tested exhaustively on their own. The client renders and never decides outcomes.

The full design is in [`docs/superpowers/specs`](docs/superpowers/specs/2026-09-27-banca-design.md).

## Stack

Kotlin, Ktor, WebSockets, PostgreSQL, React, TypeScript, Vite, Tailwind, MCP.

Everything runs on free tiers: Supabase for database and auth, Render for the backend, Vercel for the frontend, and Ollama or a free hosted model tier for the agent.

## Running locally

You need JDK 21 or newer, Node 22, and [Ollama](https://ollama.com) with a tool-calling model for the opponent:

```bash
ollama pull qwen2.5:7b
```

To play without a model, start the backend with `MODEL_PROVIDER=passive` and the opponent will only check and call.

Players and their history are kept in Postgres when `DATABASE_URL` is set, with the migrations in [`db/migrations`](db/migrations) applied in order. Without it the server keeps them in memory and forgets them when it stops, which is enough to try the game. Settings are read from the environment, then from `backend/.env`.

```bash
cd backend && ./gradlew run
```

```bash
cd frontend && npm install && npm run dev
```

Then open http://localhost:5173. The backend listens on port 8080, and the frontend talks to it over the WebSocket described in [`docs/protocol.md`](docs/protocol.md).

Run the tests with `./gradlew test` in `backend/`. The Postgres store is tested as well when `TEST_DATABASE_URL` points at a database with the migrations applied; never point it at one whose data you want to keep.

## Author

Francisco Aragão Dias © [GitHub](https://github.com/xicoooooo) · [LinkedIn](https://www.linkedin.com/in/francisco-dias-78bb13205)
