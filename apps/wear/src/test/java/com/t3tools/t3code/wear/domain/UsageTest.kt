package com.t3tools.t3code.wear.domain

import com.t3tools.t3code.wear.Fixtures
import com.t3tools.t3code.wear.wire.UsageLimits
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTest {
    private val providers = Fixtures.providers.providers

    @Test
    fun `cards show remaining quota, session first, and skip providers without limits`() {
        val cards = usageCards(listOf(EnvironmentProviders("env-1", "MacBook", providers)))

        assertEquals(listOf("Claude", "Codex"), cards.map { it.providerName })
        val claude = cards.first()
        assertEquals(listOf("Session" to 90, "Weekly" to 98), claude.windows.map { it.label to it.remainingPercent })
        assertEquals(Instant.parse("2026-11-06T23:50:00.226Z"), claude.windows.first().resetsAt)
        assertEquals(8, cards[1].windows.single().remainingPercent)
    }

    @Test
    fun `environments on the same account pool into one card with the freshest probe`() {
        val claude = providers.first { it.driver == "claudeAgent" }
        val fresher = claude.copy(
            usageLimits = claude.usageLimits!!.copy(
                checkedAt = "2026-11-06T20:10:00.000Z",
                windows = claude.usageLimits!!.windows.map { it.copy(usedPercent = 50.0) },
            ),
        )
        val otherAccount = claude.copy(usageLimits = claude.usageLimits!!.copy(credentialFingerprint = "acct-2"))

        val cards = usageCards(
            listOf(
                EnvironmentProviders("env-1", "MacBook", listOf(claude)),
                EnvironmentProviders("env-2", "VPS", listOf(fresher)),
                EnvironmentProviders("env-3", "Desktop", listOf(otherAccount)),
            ),
        )

        assertEquals(2, cards.size)
        assertEquals(listOf("MacBook", "VPS"), cards[0].environmentLabels)
        assertEquals(listOf(50, 50), cards[0].windows.map { it.remainingPercent })
        assertEquals(listOf("Desktop"), cards[1].environmentLabels)
    }

    @Test
    fun `a failed probe keeps its card with the problem`() {
        val failed = providers.first().copy(
            usageLimits = UsageLimits(
                checkedAt = "2026-11-06T20:00:00.000Z",
                unavailable = com.t3tools.t3code.wear.wire.UsageUnavailable("probeFailed", "Not signed in"),
            ),
        )
        val card = usageCards(listOf(EnvironmentProviders("env", "Mac", listOf(failed)))).single()
        assertEquals("Not signed in", card.problem)
    }

    @Test
    fun `reset times read as a countdown within a day`() {
        val now = Instant.parse("2026-11-06T20:00:00Z")
        assertEquals("Resets in 3h 50m", formatResetsIn(Instant.parse("2026-11-06T23:50:00Z"), now))
        assertEquals("Resets in 5m", formatResetsIn(Instant.parse("2026-11-06T20:05:30Z"), now))
        assertEquals("Resetting now", formatResetsIn(Instant.parse("2026-11-06T19:59:00Z"), now))
        assertEquals("Resets Thu 6:00 AM", formatResetsIn(Instant.parse("2026-11-12T06:00:00Z"), now, ZoneOffset.UTC))
    }
}
