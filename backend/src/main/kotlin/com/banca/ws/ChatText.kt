package com.banca.ws

/**
 * What is done to a message a player typed before anyone else sees it.
 *
 * Nobody watches over these rooms, so the little that can be done by rule is
 * done here: a message is kept short and to one line, links are taken out so a
 * room cannot be used to send people elsewhere, and the worst words are
 * starred out. It will not stop someone determined to be unpleasant. For that,
 * every player can mute anyone they would rather not hear from.
 */
object ChatText {
    const val MAX_LENGTH = 140

    private val LINK = Regex("""(?i)\b(?:https?://|www\.)\S+|\b[\w-]+\.(?:com|net|org|io|gg|ly|me|co|pt|xyz|info|biz|ru|app)\b\S*""")

    // Deliberately short: the words that are only ever an insult, in the two
    // languages most players here will write in. A longer list catches more
    // innocent words than it stops unkind ones.
    private val STARRED = listOf(
        "fuck", "fucking", "fucker", "shit", "bitch", "cunt", "asshole", "bastard", "whore", "slut", "retard",
        "nigger", "nigga", "faggot", "fag",
        "merda", "caralho", "foda", "fodasse", "puta", "cabrao", "cabrão", "paneleiro", "otario", "otário",
    ).let { words -> Regex("""(?i)(?<![\p{L}])(?:${words.joinToString("|") { Regex.escape(it) }})(?![\p{L}])""") }

    /** The message as others will see it, or null if there is nothing left of it to show. */
    fun clean(raw: String): String? {
        val oneLine = raw
            .filterNot { it.isISOControl() && it != '\n' && it != '\t' }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_LENGTH)
            .trim()
        if (oneLine.isEmpty()) return null

        return oneLine
            .replace(LINK, "[link]")
            .replace(STARRED) { match -> match.value.first() + "*".repeat(match.value.length - 1) }
    }
}
