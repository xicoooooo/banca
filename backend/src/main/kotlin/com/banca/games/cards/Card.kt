package com.banca.games.cards

enum class Suit(val symbol: Char) {
    CLUBS('c'),
    DIAMONDS('d'),
    HEARTS('h'),
    SPADES('s');

    companion object {
        fun of(symbol: Char): Suit =
            entries.firstOrNull { it.symbol == symbol.lowercaseChar() }
                ?: throw IllegalArgumentException("Unknown suit '$symbol'")
    }
}

enum class Rank(val value: Int, val symbol: Char) {
    TWO(2, '2'),
    THREE(3, '3'),
    FOUR(4, '4'),
    FIVE(5, '5'),
    SIX(6, '6'),
    SEVEN(7, '7'),
    EIGHT(8, '8'),
    NINE(9, '9'),
    TEN(10, 'T'),
    JACK(11, 'J'),
    QUEEN(12, 'Q'),
    KING(13, 'K'),
    ACE(14, 'A');

    companion object {
        fun of(symbol: Char): Rank =
            entries.firstOrNull { it.symbol == symbol.uppercaseChar() }
                ?: throw IllegalArgumentException("Unknown rank '$symbol'")
    }
}

data class Card(val rank: Rank, val suit: Suit) {

    override fun toString(): String = "${rank.symbol}${suit.symbol}"

    companion object {
        /** Parses "As", "Td", "7c". Used heavily by tests, where readability matters. */
        fun of(text: String): Card {
            require(text.length == 2) { "A card is two characters like 'As', got '$text'" }
            return Card(Rank.of(text[0]), Suit.of(text[1]))
        }

        fun allOf(vararg texts: String): List<Card> = texts.map(::of)
    }
}
