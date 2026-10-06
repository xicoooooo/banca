package com.banca.games.roulette

import kotlin.random.Random

enum class PocketColor {
    GREEN,
    RED,
    BLACK,
}

/**
 * A European wheel: the numbers 1 to 36 and a single zero. The zero is what
 * gives the house its edge, since every bet is paid as if it were not there.
 */
object Wheel {
    const val POCKETS = 37

    private val RED = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)

    /** The pockets in the order they sit round the wheel, clockwise from zero. */
    val ORDER = listOf(
        0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10,
        5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26,
    )

    fun colorOf(pocket: Int): PocketColor = when {
        pocket == 0 -> PocketColor.GREEN
        pocket in RED -> PocketColor.RED
        else -> PocketColor.BLACK
    }

    fun spin(random: Random): Int = random.nextInt(POCKETS)
}

/**
 * A bet on the layout: the numbers it covers and what it pays. Every bet is
 * one of these, however it is named at the table, which is why settling a spin
 * needs to know nothing else about it.
 *
 * A bet covering n numbers pays 36 / n - 1 to one. That one rule gives every
 * payout on the table, and with 37 pockets it leaves the house the same edge
 * on all of them.
 */
sealed interface Bet {
    val numbers: Set<Int>

    /** What a winning chip earns, on top of coming back. */
    val pays: Int get() = 36 / numbers.size - 1

    /** Inside bets are on the numbers themselves; outside bets are the boxes around them. */
    val isInside: Boolean

    /** One number. */
    data class Straight(val number: Int) : Bet {
        init {
            require(number in 0..36) { "There is no pocket $number" }
        }

        override val numbers get() = setOf(number)
        override val isInside get() = true
    }

    /** Two numbers that touch on the layout. */
    data class Split(val first: Int, val second: Int) : Bet {
        init {
            val (low, high) = listOf(first, second).sorted()
            val sideBySide = high - low == 1 && low % 3 != 0
            val oneRowApart = high - low == 3
            val withZero = low == 0 && high in 1..3
            require(low in 0..36 && high in 1..36 && (withZero || (low >= 1 && (sideBySide || oneRowApart)))) {
                "$first and $second do not touch on the layout"
            }
        }

        override val numbers get() = setOf(first, second)
        override val isInside get() = true
    }

    /** A row of three, named by its lowest number: 1, 4, 7 and so on. */
    data class Street(val first: Int) : Bet {
        init {
            require(first in 1..34 && first % 3 == 1) { "A street starts on 1, 4, 7 and so on, not $first" }
        }

        override val numbers get() = (first..first + 2).toSet()
        override val isInside get() = true
    }

    /** Four numbers meeting at a corner, named by the lowest. */
    data class Corner(val first: Int) : Bet {
        init {
            require(first in 1..32 && first % 3 != 0) { "No four numbers meet at a corner starting on $first" }
        }

        override val numbers get() = setOf(first, first + 1, first + 3, first + 4)
        override val isInside get() = true
    }

    /** Two rows of three, named by the lowest number. */
    data class SixLine(val first: Int) : Bet {
        init {
            require(first in 1..31 && first % 3 == 1) { "A six line starts on 1, 4, 7 and so on, not $first" }
        }

        override val numbers get() = (first..first + 5).toSet()
        override val isInside get() = true
    }

    /** A third of the numbers in order: 1 to 12, 13 to 24, or 25 to 36. */
    data class Dozen(val which: Int) : Bet {
        init {
            require(which in 1..3) { "There are three dozens, not a dozen $which" }
        }

        override val numbers get() = ((which - 1) * 12 + 1..which * 12).toSet()
        override val isInside get() = false
    }

    /** One of the three columns of the layout. Column 1 holds 1, 4, 7 and so on. */
    data class Column(val which: Int) : Bet {
        init {
            require(which in 1..3) { "There are three columns, not a column $which" }
        }

        override val numbers get() = (1..36).filter { (it - 1) % 3 == which - 1 }.toSet()
        override val isInside get() = false
    }

    data object Red : Bet {
        override val numbers = (1..36).filter { Wheel.colorOf(it) == PocketColor.RED }.toSet()
        override val isInside get() = false
    }

    data object Black : Bet {
        override val numbers = (1..36).filter { Wheel.colorOf(it) == PocketColor.BLACK }.toSet()
        override val isInside get() = false
    }

    data object Even : Bet {
        override val numbers = (1..36).filter { it % 2 == 0 }.toSet()
        override val isInside get() = false
    }

    data object Odd : Bet {
        override val numbers = (1..36).filter { it % 2 == 1 }.toSet()
        override val isInside get() = false
    }

    /** 1 to 18. */
    data object Low : Bet {
        override val numbers = (1..18).toSet()
        override val isInside get() = false
    }

    /** 19 to 36. */
    data object High : Bet {
        override val numbers = (19..36).toSet()
        override val isInside get() = false
    }
}

data class Wager(val bet: Bet, val amount: Long)

/** [returned] is every chip coming back for this wager, the stake included; nought for a loser. */
data class WagerResult(val wager: Wager, val returned: Long) {
    val won: Boolean get() = returned > 0
}

data class SpinResult(
    val pocket: Int,
    val color: PocketColor,
    val wagers: List<WagerResult>,
    val staked: Long,
    /** What the spin did to the player's chips, all told. */
    val net: Long,
)

/**
 * Settles a spin. Roulette has no decisions after the bets are down, so a
 * whole round is this one function: where the ball landed, and what each
 * wager on the layout comes to.
 */
object Roulette {
    fun settle(wagers: List<Wager>, pocket: Int): SpinResult {
        require(pocket in 0 until Wheel.POCKETS) { "There is no pocket $pocket" }
        require(wagers.all { it.amount > 0 }) { "A wager must be for at least one chip" }

        val results = wagers.map { wager ->
            WagerResult(wager, returned = if (pocket in wager.bet.numbers) wager.amount * (wager.bet.pays + 1) else 0)
        }
        val staked = wagers.sumOf { it.amount }
        return SpinResult(
            pocket = pocket,
            color = Wheel.colorOf(pocket),
            wagers = results,
            staked = staked,
            net = results.sumOf { it.returned } - staked,
        )
    }
}
