#!/usr/bin/env node
// A throwaway terminal player for the blackjack socket, for trying the server
// before the table exists in the browser. Run: node scripts/blackjack-cli.mjs
import readline from 'node:readline'

const url = process.argv[2] ?? 'ws://localhost:8080/ws/blackjack'
const ws = new WebSocket(url)
const rl = readline.createInterface({ input: process.stdin })

// Lines are queued, so typing ahead (or piping answers in) works as well as
// answering each prompt in turn.
const typed = []
let waiting = null
rl.on('line', (line) => {
  if (waiting) {
    const answer = waiting
    waiting = null
    answer(line)
  } else {
    typed.push(line)
  }
})
// Input ending (Ctrl-D, or the end of piped answers) leaves once nothing
// typed is still waiting to be used.
let inputEnded = false
rl.on('close', () => {
  inputEnded = true
  if (waiting) ws.close()
})

function prompt(text, answer) {
  process.stdout.write(text)
  if (typed.length > 0) {
    const line = typed.shift()
    console.log(line)
    answer(line)
  } else if (inputEnded) {
    console.log()
    ws.close()
  } else {
    waiting = answer
  }
}

const SUITS = { s: '♠', h: '♥', d: '♦', c: '♣' }
const card = (c) => (c ? `${c[0] === 'T' ? '10' : c[0]}${SUITS[c[1]]}` : '??')
const say = (message) => ws.send(JSON.stringify(message))

const KEYS = { h: 'hit', s: 'stand', d: 'double', p: 'split', i: 'insure', n: 'decline_insurance' }

function show(view) {
  console.log(`\n── Round ${view.roundNumber} · ${view.phase.toUpperCase()} · chips ${view.stack} ──`)
  if (view.dealer) {
    console.log(`Dealer  ${view.dealer.cards.map(card).join(' ')}   (${view.dealer.total}${view.dealer.soft ? ' soft' : ''})`)
  }
  view.hands.forEach((hand, index) => {
    const marker = index === view.activeHand ? '▶' : ' '
    const outcome = hand.outcome ? `  → ${hand.outcome.toUpperCase()}, ${hand.returned} back` : ''
    console.log(
      `${marker} You   ${hand.cards.map(card).join(' ')}   (${hand.total}${hand.soft ? ' soft' : ''})  bet ${hand.bet}  ${hand.status}${outcome}`,
    )
  })
  if (view.result) {
    const net = view.result.net
    console.log(`Result: ${net > 0 ? '+' : ''}${net} chips${view.result.refilled ? '  (out of chips, the house staked you again)' : ''}`)
  }
}

function ask(view) {
  const legal = view.legal

  if (legal.bet) {
    const suggested = view.lastBet ?? 50
    prompt(`Bet ${view.minBet}-${view.maxBet} [${suggested}], or q to quit: `, (answer) => {
      const text = answer.trim().toLowerCase()
      if (text === 'q') return quit()
      const amount = text === '' ? suggested : Number(text)
      if (!Number.isInteger(amount)) {
        console.log('Type a whole number of chips.')
        return ask(view)
      }
      say({ type: 'bet', amount })
    })
    return
  }

  const options = [
    legal.hit && '[h]it',
    legal.stand && '[s]tand',
    legal.double && '[d]ouble',
    legal.split && 's[p]lit',
    legal.insurance && `[i]nsure for ${view.insuranceCost}`,
    view.phase === 'insurance' && '[n]o insurance',
  ].filter(Boolean)

  prompt(`${options.join('  ')}  q to quit: `, (answer) => {
    const key = answer.trim().toLowerCase()
    if (key === 'q') return quit()
    if (!KEYS[key]) {
      console.log('Not one of the options.')
      return ask(view)
    }
    say({ type: 'act', action: KEYS[key] })
  })
}

function quit() {
  rl.close()
  ws.close()
}

let latest = null

ws.onopen = () => console.log(`Connected to ${url}`)
ws.onmessage = (event) => {
  const message = JSON.parse(event.data)
  if (message.type === 'error') {
    console.log(`Refused: ${message.message}`)
  } else {
    latest = message.view
    show(latest)
  }
  if (latest) ask(latest)
}
ws.onerror = () => {
  console.log(`Could not connect to ${url}. Is the backend running?`)
  process.exit(1)
}
ws.onclose = () => process.exit(0)
