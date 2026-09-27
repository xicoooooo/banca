package com.banca.games.poker

enum class Street {
    PREFLOP,
    FLOP,
    TURN,
    RIVER,
    SHOWDOWN;

    val boardSize: Int
        get() = when (this) {
            PREFLOP -> 0
            FLOP -> 3
            TURN -> 4
            RIVER, SHOWDOWN -> 5
        }

    fun next(): Street {
        require(this != SHOWDOWN) { "Nothing follows the showdown" }
        return entries[ordinal + 1]
    }
}
