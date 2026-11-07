package com.t3tools.t3code.wear.domain

import com.t3tools.t3code.wear.wire.ShellSnapshot
import com.t3tools.t3code.wear.wire.ThreadShell
import java.time.Instant

/** Mirrors apps/mobile `ThreadListV2Status`, so the watch and phone agree on what needs you. */
enum class ThreadStatus(val label: String, val needsYou: Boolean, val rank: Int) {
    Approval("Approval", needsYou = true, rank = 0),
    Input("Input", needsYou = true, rank = 1),
    Request("Request", needsYou = true, rank = 2),
    Working("Working", needsYou = false, rank = 3),
    Waiting("Waiting", needsYou = false, rank = 4),
    Failed("Failed", needsYou = false, rank = 5),
    Limited("Limited", needsYou = false, rank = 5),
    Ready("Ready", needsYou = false, rank = 6),
}

data class ThreadRow(
    val environmentId: String,
    val environmentLabel: String,
    val threadId: String,
    val title: String,
    val projectTitle: String?,
    val status: ThreadStatus,
    val preview: String?,
    val model: String?,
    val updatedAt: Instant,
    val itemCount: Int,
)

private val LIVE_RUN_STATUSES = setOf("preparing", "queued", "starting", "running", "waiting")
private val NON_EXCLUSIVE_REQUEST_KINDS = setOf("user_input", "auth_refresh")

fun isListed(thread: ThreadShell): Boolean =
    thread.lineage.relationshipToParent != "subagent" &&
        thread.lineage.sideChat != true &&
        thread.archivedAt == null &&
        thread.deletedAt == null

/** `shellRuntime` in client-runtime/state/models.ts: null when the thread never ran. */
internal fun runtimeStatus(thread: ThreadShell): String? {
    val parkAtIdle = thread.status != "failed" &&
        thread.pendingBackgroundTasks.any {
            it.awaited == true || it.kind == "subagent" || it.kind == "background_task"
        }
    if (thread.latestRunId == null && thread.activeProviderThreadId == null && !parkAtIdle) return null
    return if (parkAtIdle) "idle" else (thread.activityRunStatus ?: thread.status)
}

private fun isChildRunLive(thread: ThreadShell): Boolean =
    thread.activityRunStatus != null ||
        thread.status in setOf("preparing", "starting", "running", "waiting")

/**
 * Subagent approvals and side-chat questions surface on the thread the user sees, as
 * `pendingSubagentRequestsByThread` in client-runtime/state/threadShell.ts does.
 */
internal fun rolledUpRequests(threads: List<ThreadShell>): Map<String, ThreadStatus> {
    val byId = threads.associateBy { it.id }
    val flags = HashMap<String, ThreadStatus>()
    fun flag(parentId: String, status: ThreadStatus) {
        if (flags[parentId] != ThreadStatus.Approval) flags[parentId] = status
    }
    for (child in threads) {
        val kind = child.pendingRuntimeRequest?.kind ?: continue
        if (kind == "auth_refresh") continue
        val parentId = child.lineage.parentThreadId ?: continue
        if (child.lineage.sideChat == true) {
            flag(parentId, if (kind == "user_input") ThreadStatus.Input else ThreadStatus.Approval)
            continue
        }
        // The coordinating agent answers a subagent's questions; only approvals need the user.
        if (child.lineage.relationshipToParent != "subagent" || kind == "user_input" || !isChildRunLive(child)) continue
        var next: String? = parentId
        val seen = HashSet<String>()
        while (next != null && seen.add(next)) {
            flag(next, ThreadStatus.Approval)
            val parent = byId[next]
            next = if (parent?.lineage?.relationshipToParent == "subagent" || parent?.lineage?.sideChat == true) {
                parent.lineage.parentThreadId
            } else {
                null
            }
        }
    }
    return flags
}

fun resolveStatus(thread: ThreadShell, rolledUp: ThreadStatus? = null): ThreadStatus {
    val request = thread.pendingRuntimeRequest?.kind
    if ((request != null && request !in NON_EXCLUSIVE_REQUEST_KINDS) || rolledUp == ThreadStatus.Approval) {
        return ThreadStatus.Approval
    }
    if (request == "user_input" || rolledUp == ThreadStatus.Input) return ThreadStatus.Input
    if ((thread.pendingGuestMessageCount ?: 0) > 0) return ThreadStatus.Request
    val runtime = runtimeStatus(thread)
    if (runtime == "running" && thread.blockedOnThreadWait == true) return ThreadStatus.Waiting
    if (runtime != null && runtime in LIVE_RUN_STATUSES) return ThreadStatus.Working
    if (runtime == "idle") return ThreadStatus.Waiting
    if (runtime == "failed") {
        return if (thread.lastErrorClass == "usage_limit") ThreadStatus.Limited else ThreadStatus.Failed
    }
    return ThreadStatus.Ready
}

fun parseInstant(value: String?): Instant =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

fun threadRows(environmentId: String, environmentLabel: String, shell: ShellSnapshot): List<ThreadRow> {
    val projects = shell.projects.associateBy { it.id }
    val rolledUp = rolledUpRequests(shell.threads)
    return shell.threads.filter(::isListed).map { thread ->
        ThreadRow(
            environmentId = environmentId,
            environmentLabel = environmentLabel,
            threadId = thread.id,
            title = thread.title.ifBlank { "Untitled thread" },
            projectTitle = projects[thread.projectId]?.title,
            status = resolveStatus(thread, rolledUp[thread.id]),
            preview = thread.latestVisibleMessage?.text?.let(::plainPreview),
            model = thread.modelSelection?.model,
            updatedAt = parseInstant(thread.latestUserMessageAt ?: thread.updatedAt).coerceAtLeast(parseInstant(thread.updatedAt)),
            itemCount = thread.itemCount,
        )
    }
}

/** What needs you first, then live work, then everything else by recency. */
val ThreadRowOrder: Comparator<ThreadRow> =
    compareBy<ThreadRow> { if (it.status.needsYou || it.status == ThreadStatus.Working) it.status.rank else 10 }
        .thenByDescending { it.updatedAt }

private fun Instant.coerceAtLeast(other: Instant): Instant = if (isBefore(other)) other else this

/** One line of message text for list rows, without markdown markers. */
fun plainPreview(text: String, maxLength: Int = 120): String {
    val flat = watchText(text).replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= maxLength) flat else flat.take(maxLength - 1).trimEnd() + "…"
}
