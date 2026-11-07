package com.t3tools.t3code.wear.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The slice of packages/contracts the watch reads. Everything else on the wire is ignored, so
 * newer servers keep decoding; fields are defaulted where the schema marks them optional.
 */
val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/** `OrchestrationV2ShellSnapshot` from `GET /api/orchestration/shell`. */
@Serializable
data class ShellSnapshot(
    val snapshotSequence: Long = 0,
    val threads: List<ThreadShell> = emptyList(),
    val projects: List<ProjectShell> = emptyList(),
)

@Serializable
data class ProjectShell(
    val id: String,
    val title: String,
    val archivedAt: String? = null,
)

@Serializable
data class ThreadLineage(
    val parentThreadId: String? = null,
    val relationshipToParent: String? = null,
    val rootThreadId: String? = null,
    val sideChat: Boolean? = null,
)

@Serializable
data class ModelSelection(
    val instanceId: String? = null,
    val model: String? = null,
)

@Serializable
data class PendingRuntimeRequest(
    val id: String,
    val kind: String,
    val createdAt: String? = null,
)

@Serializable
data class LatestVisibleMessage(
    val role: String,
    val text: String,
)

@Serializable
data class PendingBackgroundTask(
    val kind: String = "",
    val awaited: Boolean? = null,
)

/** `OrchestrationV2ThreadShell`. */
@Serializable
data class ThreadShell(
    val id: String,
    val projectId: String,
    val title: String = "",
    val providerInstanceId: String? = null,
    val modelSelection: ModelSelection? = null,
    val branch: String? = null,
    val lineage: ThreadLineage = ThreadLineage(),
    val delegatedFrom: JsonObject? = null,
    val latestRunId: String? = null,
    val activeProviderThreadId: String? = null,
    val activityRunStatus: String? = null,
    val activityRunStartedAt: String? = null,
    val blockedOnThreadWait: Boolean? = null,
    val status: String = "idle",
    val lastError: String? = null,
    val lastErrorClass: String? = null,
    val usageLimitResetAt: String? = null,
    val pendingRuntimeRequest: PendingRuntimeRequest? = null,
    val pendingGuestMessageCount: Int? = null,
    val pendingBackgroundTasks: List<PendingBackgroundTask> = emptyList(),
    val latestVisibleMessage: LatestVisibleMessage? = null,
    val latestUserMessageAt: String? = null,
    val itemCount: Int = 0,
    val visibleItemCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val archivedAt: String? = null,
    val deletedAt: String? = null,
    val settledAt: String? = null,
    val snoozedUntil: String? = null,
    val pinnedAt: String? = null,
)

/** One row of `visibleTurnItems`; `item` stays raw so unknown item types never break decoding. */
@Serializable
data class ProjectedTurnItem(
    val position: Int = 0,
    val item: JsonObject,
)

@Serializable
data class ThreadProjection(
    val visibleTurnItems: List<ProjectedTurnItem> = emptyList(),
)

/** `OrchestrationV2ThreadBoundedSnapshot` from `GET .../threads/:id/bounded`. */
@Serializable
data class ThreadBoundedSnapshot(
    val projection: ThreadProjection = ThreadProjection(),
    val historyCursor: String? = null,
    val hasMoreHistory: Boolean = false,
)

/** `OrchestrationV2ThreadHistoryPage` from `GET .../threads/:id/history`. */
@Serializable
data class ThreadHistoryPage(
    val items: List<ProjectedTurnItem> = emptyList(),
    val nextCursor: String? = null,
    val hasMoreHistory: Boolean = false,
)

@Serializable
data class UsageWindow(
    val id: String,
    val kind: String = "other",
    val label: String,
    val usedPercent: Double = 0.0,
    val resetsAt: String? = null,
    val windowDurationMins: Int? = null,
)

@Serializable
data class UsageUnavailable(
    val reason: String,
    val message: String? = null,
)

@Serializable
data class UsageLimits(
    val checkedAt: String,
    val windows: List<UsageWindow> = emptyList(),
    val credentialFingerprint: String? = null,
    val unavailable: UsageUnavailable? = null,
)

/** `ServerProvider`, trimmed to what the usage screen shows. */
@Serializable
data class ServerProvider(
    val instanceId: String,
    val driver: String,
    val displayName: String? = null,
    val accentColor: String? = null,
    val enabled: Boolean = false,
    val status: String = "",
    val usageLimits: UsageLimits? = null,
)

@Serializable
data class ProvidersPayload(
    val providers: List<ServerProvider> = emptyList(),
)

/** Error bodies the environment HTTP API returns, e.g. `{"_tag":"...","reason":"..."}`. */
@Serializable
data class EnvironmentErrorBody(
    val _tag: String? = null,
    val code: String? = null,
    val reason: String? = null,
    val message: String? = null,
)
