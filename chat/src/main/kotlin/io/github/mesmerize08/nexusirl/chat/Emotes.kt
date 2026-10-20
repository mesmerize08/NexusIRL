// Port of Moblin/Integrations/Emotes/Emotes.swift `createSegments` @97e26d2.
package io.github.mesmerize08.nexusirl.chat

/** A third-party emote (BTTV, FFZ, 7TV) matched by its name in chat text. */
data class ThirdPartyEmote(val url: String, val stillUrl: String? = null)

/** Third-party emotes by name; filled per channel by the emote providers. */
class EmoteSet(emotes: Map<String, ThirdPartyEmote> = emptyMap()) {
    @Volatile
    private var emotes: Map<String, ThirdPartyEmote> = emotes

    fun set(emotes: Map<String, ThirdPartyEmote>) {
        this.emotes = emotes
    }

    val isReady: Boolean
        get() = emotes.isNotEmpty()

    /**
     * Splits [text] into word segments; a word that is a known emote becomes an emote segment
     * followed by an empty text segment, like upstream. [id] continues the caller's numbering.
     */
    fun createSegments(text: String, id: IntArray): List<ChatPostSegment> {
        val known = emotes
        val segments = mutableListOf<ChatPostSegment>()
        for (word in text.split { it.isWhitespace() }.filter { it.isNotEmpty() }) {
            val emote = known[word]
            if (emote == null) {
                segments += ChatPostSegment(id[0]++, text = "$word ")
                continue
            }
            segments += ChatPostSegment(id[0]++, text = "", url = ChatEmote(emote.url, emote.stillUrl ?: emote.url, word))
            segments += ChatPostSegment(id[0]++, text = "")
        }
        return segments
    }

    private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (char in this) {
            if (isSeparator(char)) {
                words += current.toString()
                current.clear()
            } else {
                current.append(char)
            }
        }
        words += current.toString()
        return words
    }
}
