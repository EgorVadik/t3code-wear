package com.t3tools.t3code.wear.domain

import com.t3tools.t3code.wear.wire.ServerProvider
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class UsageWindowRow(
    val label: String,
    val kind: String,
    val remainingPercent: Int,
    val resetsAt: Instant?,
)

data class UsageCard(
    val key: String,
    val providerName: String,
    val driver: String,
    val accentColor: String?,
    val environmentLabels: List<String>,
    val windows: List<UsageWindowRow>,
    val checkedAt: Instant,
    val problem: String?,
)

data class EnvironmentProviders(
    val environmentId: String,
    val environmentLabel: String,
    val providers: List<ServerProvider>,
)

private val KIND_ORDER = mapOf("session" to 0, "weekly" to 1, "monthly" to 2, "other" to 3)

/**
 * One card per provider account. Environments signed in to the same account report the same
 * `credentialFingerprint`, so their windows are pooled like the phone's limits page does, and
 * the freshest probe wins.
 */
fun usageCards(environments: List<EnvironmentProviders>): List<UsageCard> {
    val pooled = LinkedHashMap<String, UsageCard>()
    for (environment in environments) {
        for (provider in environment.providers) {
            if (!provider.enabled) continue
            val limits = provider.usageLimits ?: continue
            if (limits.unavailable?.reason == "unsupported") continue
            if (limits.windows.isEmpty() && limits.unavailable == null) continue
            val key = provider.driver + ":" +
                (limits.credentialFingerprint ?: "${environment.environmentId}/${provider.instanceId}")
            val card = UsageCard(
                key = key,
                providerName = provider.displayName ?: provider.driver,
                driver = provider.driver,
                accentColor = provider.accentColor,
                environmentLabels = listOf(environment.environmentLabel),
                windows = limits.windows
                    .sortedWith(compareBy({ KIND_ORDER[it.kind] ?: 3 }, { it.label }))
                    .map {
                        UsageWindowRow(
                            label = it.label,
                            kind = it.kind,
                            remainingPercent = (100.0 - it.usedPercent).coerceIn(0.0, 100.0).roundToInt(),
                            resetsAt = it.resetsAt?.let(::parseInstant)?.takeIf { at -> at != Instant.EPOCH },
                        )
                    },
                checkedAt = parseInstant(limits.checkedAt),
                problem = limits.unavailable?.let { it.message ?: "Couldn't check limits" },
            )
            val existing = pooled[key]
            pooled[key] = when {
                existing == null -> card
                card.checkedAt.isAfter(existing.checkedAt) ->
                    card.copy(environmentLabels = (existing.environmentLabels + card.environmentLabels).distinct())
                else -> existing.copy(environmentLabels = (existing.environmentLabels + card.environmentLabels).distinct())
            }
        }
    }
    return pooled.values.toList()
}

private val RESET_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

fun formatResetsIn(resetsAt: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String? {
    resetsAt ?: return null
    val remaining = Duration.between(now, resetsAt)
    if (remaining.isNegative || remaining.isZero) return "Resetting now"
    val minutes = remaining.toMinutes()
    return when {
        minutes < 1 -> "Resets in <1m"
        minutes < 60 -> "Resets in ${minutes}m"
        minutes < 24 * 60 -> "Resets in ${minutes / 60}h ${minutes % 60}m"
        else -> "Resets " + RESET_DAY_FORMAT.format(resetsAt.atZone(zone))
    }
}

fun formatAgo(then: Instant, now: Instant): String {
    val seconds = Duration.between(then, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}
