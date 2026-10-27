// Port of executeIfUserAllowedToUseChatBot and isChannelOwner in
// Moblin/Various/Model/ModelChatBot.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat.bot

import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotPermissionsCommand

/** Whether a user may run a command now. */
sealed interface ChatBotDecision {
    data object Allowed : ChatBotDecision

    data object NotAllowed : ChatBotDecision

    /** Allowed, but run too recently; [secondsLeft] as upstream rounds it (towards zero). */
    data class Cooldown(val secondsLeft: Int) : ChatBotDecision
}

/**
 * Remembers when each command group last ran, for cooldowns (upstream keeps this on each
 * permissions object). [nowMillis] is a monotonic clock.
 */
class ChatBotPermissionCheck(private val nowMillis: () -> Long) {
    private val latestExecution = mutableMapOf<String, Long>()

    /**
     * Upstream `executeIfUserAllowedToUseChatBot` for the command group [key]. The owner and
     * allowed moderators skip the cooldown. NexusIRL does not look up subscriber tiers, so a
     * minimum tier above 1 on Twitch lets no subscriber through as a subscriber.
     */
    fun check(
        key: String,
        permissions: SettingsChatBotPermissionsCommand,
        message: ChatBotMessage,
        twitchChannelName: String,
        kickChannelName: String,
    ): ChatBotDecision {
        val now = nowMillis()
        if (isChannelOwner(message, twitchChannelName, kickChannelName) || (message.isModerator && permissions.moderatorsEnabled)) {
            latestExecution[key] = now
            return ChatBotDecision.Allowed
        }
        val subscriberAllowed = message.isSubscriber &&
            permissions.subscribersEnabled &&
            (message.platform != ChatPlatform.TWITCH || permissions.minimumSubscriberTier <= 1)
        if (!subscriberAllowed && !permissions.othersEnabled) {
            return ChatBotDecision.NotAllowed
        }
        val cooldown = permissions.cooldown
        val latest = latestExecution[key]
        if (cooldown != null && latest != null) {
            val leftMillis = cooldown * 1000L - (now - latest)
            if (leftMillis >= 0) {
                return ChatBotDecision.Cooldown((leftMillis / 1000).toInt())
            }
        }
        latestExecution[key] = now
        return ChatBotDecision.Allowed
    }

    companion object {
        /** Upstream `isChannelOwner`. */
        fun isChannelOwner(message: ChatBotMessage, twitchChannelName: String, kickChannelName: String): Boolean {
            val user = message.user ?: return false
            return when (message.platform) {
                ChatPlatform.TWITCH -> user.equals(twitchChannelName, ignoreCase = true)
                ChatPlatform.KICK -> user.equals(kickChannelName, ignoreCase = true)
                ChatPlatform.YOUTUBE -> message.isOwner
                else -> false
            }
        }
    }
}
