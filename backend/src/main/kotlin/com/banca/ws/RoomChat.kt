package com.banca.ws

import java.util.UUID

/**
 * What has been said at a shared table, and the rule about how often anyone
 * may say more. The same at every game: a table decides who is sitting at it,
 * and this decides what they may say to each other.
 */
class RoomChat {
    private val lines = ArrayDeque<ChatLine>()
    private val lastSpoke = HashMap<UUID, Long>()

    /** What has been said lately, oldest first, for someone walking in. */
    fun recent(): List<ChatLine> = lines.toList()

    /**
     * Takes something a player wants to say: a set phrase by its id, or a
     * typed message, which is tidied first. Returns the line for the table to
     * pass on, or refuses it.
     */
    fun say(playerId: UUID, name: String, phraseId: String?, typed: String?): ChatLine {
        val line = if (phraseId != null) {
            val phrase = RoomPhrases.find(phraseId) ?: throw IllegalArgumentException("That is not one of the room's phrases")
            ChatLine(from = name, text = phrase.text, emote = phrase.emote)
        } else {
            val text = typed?.let(ChatText::clean) ?: throw IllegalArgumentException("There is nothing there to say")
            ChatLine(from = name, text = text, emote = false)
        }

        // Not too often, so one player cannot fill the screen.
        val now = System.nanoTime() / 1_000_000
        check(now - (lastSpoke[playerId] ?: Long.MIN_VALUE / 2) >= EVERY_MS) { "Give it a moment before saying more" }
        lastSpoke[playerId] = now

        lines.addLast(line)
        while (lines.size > KEPT) lines.removeFirst()
        return line
    }

    /** Takes a line from Banca, which has no clock to wait on: it speaks only when a hand ends. */
    fun fromBanca(text: String): ChatLine {
        val line = ChatLine(from = "Banca", text = text, emote = false, banca = true)
        lines.addLast(line)
        while (lines.size > KEPT) lines.removeFirst()
        return line
    }

    fun forget(playerId: UUID) {
        lastSpoke.remove(playerId)
    }

    private companion object {
        const val EVERY_MS = 1_500L
        const val KEPT = 30
    }
}
