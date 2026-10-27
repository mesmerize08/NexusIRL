package io.github.mesmerize08.nexusirl.chat.bot

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotAlias
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatBotCommandTest {
    private fun message(vararg words: String) = ChatBotMessage(
        platform = ChatPlatform.TWITCH,
        user = "viewer",
        isOwner = false,
        isModerator = true,
        isSubscriber = false,
        userId = "1",
        segments = words.mapIndexed { index, word -> ChatPostSegment(index, text = "$word ") },
    )

    private fun main(word: String) = matchArgument(word, ChatBotMainArgument.entries.toTypedArray())

    @Test
    fun wordsAfterMoblin() {
        val command = ChatBotCommand.parse(message("!Moblin", "scene", "Screen"), emptyList())!!
        assertEquals(ChatBotMainArgument.SCENE, command.popFirstArgument(ChatBotMainArgument.entries.toTypedArray()))
        assertEquals("Screen", command.popFirst())
        assertNull(command.popFirst())
        assertNull(ChatBotCommand.parse(message("hello", "scene"), emptyList()))
        assertNull(ChatBotCommand.parse(message().copy(segments = emptyList()), emptyList()))
    }

    @Test
    fun aliases() {
        val aliases = listOf(SettingsChatBotAlias("!screen", "!moblin scene Screen"))
        val command = ChatBotCommand.parse(message("!screen"), aliases)!!
        assertEquals("scene Screen", command.rest())
        // Words after the alias follow its replacement.
        assertEquals("scene Screen now", ChatBotCommand.parse(message("!screen", "now"), aliases)!!.rest())
        assertNull(ChatBotCommand.parse(message("!other"), aliases))
    }

    @Test
    fun quotedWordsAndEmotes() {
        val command = ChatBotCommand.parse(message("!moblin", "scene", "\"My", "big", "scene\"", "x"), emptyList())!!
        command.popFirst()
        assertEquals("My big scene", command.popFirst())
        assertEquals("x", command.popFirst())
        assertEquals("", ChatBotCommand.parse(message("!moblin", "\"\""), emptyList())!!.popFirst())
        // An unterminated quote gives nothing.
        assertNull(ChatBotCommand.parse(message("!moblin", "\"a", "b"), emptyList())!!.popFirst())
        val emote = ChatPostSegment(9, url = ChatEmote("https://example.com/e.png", null, "Kappa"))
        val withEmote = message("!moblin", "zoom").let { it.copy(segments = it.segments + emote + ChatPostSegment(10, text = "2 ")) }
        assertEquals("zoom 2", ChatBotCommand.parse(withEmote, emptyList())!!.rest())
    }

    @Test
    fun numbers() {
        val command = ChatBotCommand.parse(message("!moblin", "50", "500", "x"), emptyList())!!
        assertEquals(50, command.popFirstInt(1..100))
        assertNull(command.popFirstInt(1..100))
        assertNull(command.popFirstInt(1..100))
    }

    @Test
    fun argumentsForgiveSmallTypos() {
        assertEquals(ChatBotMainArgument.MUTE, main("mute"))
        assertEquals(ChatBotMainArgument.UNMUTE, main("unmute"))
        assertEquals(ChatBotMainArgument.MUTE, main("mut"))
        assertEquals(ChatBotMainArgument.SCENE, main("scen"))
        assertEquals(ChatBotMainArgument.SNAPSHOT, main("snapshto"))
        assertNull(main("xyz"))
        assertNull(main(""))
        assertEquals(ChatBotFilterArgument.FOUR_THREE, matchArgument("4:3", ChatBotFilterArgument.entries.toTypedArray()))
        assertEquals(ChatBotOnOffArgument.ON, matchArgument("on", ChatBotOnOffArgument.entries.toTypedArray()))
    }

    @Test
    fun fuzzyMatchPattern() {
        assertEquals(0, "mute ".fuzzyMatchPattern("MUTE "))
        assertEquals(0, "mute ".fuzzyMatchPattern("mu"))
        assertNull("".fuzzyMatchPattern("a"))
        assertNull("abc".fuzzyMatchPattern(""))
        assertEquals(2, "unmute ".fuzzyMatchPattern("mute", threshold = 0.34))
        // Two letters missing of six is within 0.34, three is not.
        assertEquals(0, "mute ".fuzzyMatchPattern("unmute", threshold = 0.34))
        assertNull("mut ".fuzzyMatchPattern("unmute", threshold = 0.34))
        // Patterns longer than 64 characters do not crash.
        assertNull("abc ".fuzzyMatchPattern("x".repeat(80), threshold = 0.34))
    }
}
