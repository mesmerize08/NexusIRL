// Port of Moblin/Various/ChatBotCommand.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat.bot

import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotAlias

private const val FUZZY_THRESHOLD = 0.34
private const val FUZZY_DISTANCE = 1000.0

private fun fuzzyMatches(text: String, pattern: String): Boolean =
    "$text ".fuzzyMatchPattern(pattern, threshold = FUZZY_THRESHOLD, distance = FUZZY_DISTANCE) != null

/** An argument word of a chat bot command (upstream `ChatBotArgument`). */
interface ChatBotArgument {
    val rawValue: String
}

/**
 * Upstream `matchArgument`: the argument [word] names, allowing typos as long as only one
 * argument is close (in both directions).
 */
fun <T> matchArgument(word: String, all: Array<T>): T? where T : Enum<T>, T : ChatBotArgument {
    all.firstOrNull { it.rawValue == word }?.let { return it }
    return all.filter { fuzzyMatches(it.rawValue, word) && fuzzyMatches(word, it.rawValue) }.singleOrNull()
}

/**
 * The first word after "!moblin". All upstream's commands are listed, also those NexusIRL does
 * not run, because typos are only forgiven when they are close to a single command.
 */
enum class ChatBotMainArgument(override val rawValue: String) : ChatBotArgument {
    HELP("help"),
    TTS("tts"),
    OBS("obs"),
    MAP("map"),
    LOCATION("location"),
    SNAPSHOT("snapshot"),
    MUTE("mute"),
    UNMUTE("unmute"),
    ALERT("alert"),
    FAX("fax"),
    FILTER("filter"),
    ZOOM("zoom"),
    SAY("say"),
    TESLA("tesla"),
    REACTION("reaction"),
    SCENE("scene"),
    STREAM("stream"),
    WIDGET("widget"),
    AI("ai"),
    TWITCH("twitch"),
    GIMBAL("gimbal"),
    MACRO("macro"),
    SEND("send"),
    MUSIC("music"),
    TORCH("torch"),
    VIBRATE("vibrate"),
    CUSTOM("custom"),
}

enum class ChatBotStreamArgument(override val rawValue: String) : ChatBotArgument {
    START("start"),
    STOP("stop"),
    TITLE("title"),
    CATEGORY("category"),
}

enum class ChatBotFilterArgument(override val rawValue: String) : ChatBotArgument {
    MOVIE("movie"),
    GRAYSCALE("grayscale"),
    SEPIA("sepia"),
    TRIPLE("triple"),
    TWIN("twin"),
    PIXELLATE("pixellate"),
    FOUR_THREE("4:3"),
    WHIRLPOOL("whirlpool"),
    PINCH("pinch"),
}

enum class ChatBotOnOffArgument(override val rawValue: String) : ChatBotArgument {
    ON("on"),
    OFF("off"),
}

enum class ChatBotTorchArgument(override val rawValue: String) : ChatBotArgument {
    ON("on"),
    OFF("off"),
    LEVEL("level"),
}

/** A chat message that may be a command (upstream `ChatBotMessage`). */
data class ChatBotMessage(
    val platform: ChatPlatform,
    val user: String?,
    val isOwner: Boolean,
    val isModerator: Boolean,
    val isSubscriber: Boolean,
    val userId: String?,
    val segments: List<ChatPostSegment>,
)

/**
 * A command's words after "!moblin", or after an alias's own "!moblin". Words in double quotes
 * count as one ("!moblin scene "My scene""). Emotes are skipped.
 */
class ChatBotCommand private constructor(val message: ChatBotMessage, private val parts: ArrayDeque<String>) {
    fun popFirst(): String? {
        val first = parts.removeFirstOrNull() ?: return null
        if (!first.startsWith("\"")) {
            return first
        }
        val unquoted = first.substring(1)
        if (unquoted == "\"") {
            return ""
        }
        val words = mutableListOf(unquoted)
        while (true) {
            val word = parts.removeFirstOrNull() ?: return null
            if (word.endsWith("\"")) {
                words += word.dropLast(1)
                return words.joinToString(" ")
            }
            words += word
        }
    }

    fun popFirstLowerCased(): String? = popFirst()?.lowercase()

    fun <T> popFirstArgument(all: Array<T>): T? where T : Enum<T>, T : ChatBotArgument =
        popFirstLowerCased()?.let { matchArgument(it, all) }

    fun popFirstInt(range: IntRange): Int? = popFirst()?.toIntOrNull()?.takeIf { it in range }

    fun peekFirst(): String? = parts.firstOrNull()

    fun rest(): String = parts.joinToString(" ")

    fun user(): String? = message.user

    companion object {
        /** Upstream `ChatBotCommand.init`: null unless the message starts with "!moblin" or an alias. */
        fun parse(message: ChatBotMessage, aliases: List<SettingsChatBotAlias>): ChatBotCommand? {
            val firstWord = message.segments.firstOrNull()?.text?.lowercase()?.trim() ?: return null
            val parts = ArrayDeque<String>()
            if (firstWord != "!moblin") {
                val alias = aliases.firstOrNull { it.alias == firstWord } ?: return null
                alias.replacement.split(" ").filter { it.isNotEmpty() }.drop(1).forEach { parts += it.trim() }
            }
            for (segment in message.segments.drop(1)) {
                segment.text?.let { parts += it.trim() }
            }
            return ChatBotCommand(message, parts)
        }
    }
}
