package com.banca.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTextTest {

    @Test
    fun `an ordinary message is left as it was written`() {
        assertEquals("Nice hit! 17 again?", ChatText.clean("Nice hit! 17 again?"))
        assertEquals("Olá a todos, boa sorte 🍀", ChatText.clean("Olá a todos, boa sorte 🍀"))
    }

    @Test
    fun `a message is one line, trimmed, and no longer than the limit`() {
        assertEquals("one two three", ChatText.clean("  one\n\ntwo\t three  "))
        assertEquals(ChatText.MAX_LENGTH, ChatText.clean("x".repeat(500))!!.length)
        assertEquals(139, ChatText.clean("word ".repeat(100))!!.length, "cut at the limit, and not left ending in a space")
        assertEquals("ab", ChatText.clean("a\u0000\u0007b"))
    }

    @Test
    fun `nothing is not a message`() {
        assertNull(ChatText.clean(""))
        assertNull(ChatText.clean("   \n\t "))
        assertNull(ChatText.clean("\u0000\u0001"))
    }

    @Test
    fun `links are taken out, however they are written`() {
        assertEquals("go to [link] now", ChatText.clean("go to https://example.com/a?b=c now"))
        assertEquals("[link]", ChatText.clean("WWW.Example.org/path"))
        assertEquals("see [link]", ChatText.clean("see bit.ly/abc"))
        assertEquals("I bet 2.5 times that", ChatText.clean("I bet 2.5 times that"), "a number is not a link")
        assertEquals("ok... fine", ChatText.clean("ok... fine"))
    }

    @Test
    fun `the worst words are starred out, whole words only`() {
        assertEquals("what a s*** spin", ChatText.clean("what a shit spin"))
        assertEquals("F***", ChatText.clean("FUCK"))
        assertEquals("que m****", ChatText.clean("que merda"))
        assertEquals("a classic Scunthorpe problem", ChatText.clean("a classic Scunthorpe problem"), "not when it is part of another word")
        assertEquals("disputa", ChatText.clean("disputa"))
    }
}
