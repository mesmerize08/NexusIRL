package io.github.mesmerize08.nexusirl.chat.bot

import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotPermissionsCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatBotPermissionsTest {
    private var now = 0L
    private val check = ChatBotPermissionCheck { now }

    private fun message(
        user: String = "viewer",
        platform: ChatPlatform = ChatPlatform.TWITCH,
        isModerator: Boolean = false,
        isSubscriber: Boolean = false,
        isOwner: Boolean = false,
    ) = ChatBotMessage(platform, user, isOwner, isModerator, isSubscriber, "1", emptyList())

    private fun decide(permissions: SettingsChatBotPermissionsCommand, message: ChatBotMessage) =
        check.check("scene", permissions, message, twitchChannelName = "Streamer", kickChannelName = "kicker")

    @Test
    fun ownerAndModerators() {
        val off = SettingsChatBotPermissionsCommand(moderatorsEnabled = false)
        assertEquals(ChatBotDecision.Allowed, decide(off, message(user = "streamer")))
        assertEquals(ChatBotDecision.Allowed, decide(off, message(user = "KICKER", platform = ChatPlatform.KICK)))
        assertEquals(ChatBotDecision.Allowed, decide(off, message(platform = ChatPlatform.YOUTUBE, isOwner = true)))
        // A YouTube name equal to the Twitch channel is not the owner.
        assertEquals(ChatBotDecision.NotAllowed, decide(off, message(user = "streamer", platform = ChatPlatform.YOUTUBE)))
        assertEquals(ChatBotDecision.NotAllowed, decide(off, message(isModerator = true)))
        assertEquals(ChatBotDecision.Allowed, decide(SettingsChatBotPermissionsCommand(), message(isModerator = true)))
        assertEquals(ChatBotDecision.NotAllowed, decide(SettingsChatBotPermissionsCommand(), message()))
    }

    @Test
    fun subscribersAndOthers() {
        val subscribers = SettingsChatBotPermissionsCommand(subscribersEnabled = true)
        assertEquals(ChatBotDecision.Allowed, decide(subscribers, message(isSubscriber = true)))
        assertEquals(ChatBotDecision.NotAllowed, decide(subscribers, message()))
        // Tiers are not looked up, so a minimum tier above 1 lets no Twitch subscriber in as such.
        val tier2 = subscribers.copy(minimumSubscriberTier = 2)
        assertEquals(ChatBotDecision.NotAllowed, decide(tier2, message(isSubscriber = true)))
        assertEquals(ChatBotDecision.Allowed, decide(tier2, message(isSubscriber = true, platform = ChatPlatform.KICK)))
        assertEquals(ChatBotDecision.Allowed, decide(SettingsChatBotPermissionsCommand(othersEnabled = true), message()))
    }

    @Test
    fun cooldownForEveryoneButOwnerAndModerators() {
        val permissions = SettingsChatBotPermissionsCommand(othersEnabled = true, cooldown = 10)
        assertEquals(ChatBotDecision.Allowed, decide(permissions, message()))
        now = 2_500
        assertEquals(ChatBotDecision.Cooldown(7), decide(permissions, message()))
        assertEquals(ChatBotDecision.Allowed, decide(permissions, message(isModerator = true)))
        now = 12_499
        assertEquals(ChatBotDecision.Cooldown(0), decide(permissions, message()))
        now = 12_501
        assertEquals(ChatBotDecision.Allowed, decide(permissions, message()))
        // Each command group has its own cooldown.
        assertEquals(ChatBotDecision.Allowed, check.check("zoom", permissions, message(), "Streamer", "kicker"))
    }
}
