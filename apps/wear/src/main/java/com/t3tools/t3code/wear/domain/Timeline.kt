package com.t3tools.t3code.wear.domain

import com.t3tools.t3code.wear.wire.ProjectedTurnItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What a watch-sized thread view shows: the conversation, with the work between messages folded. */
sealed interface TimelineRow {
    val key: String

    data class Message(
        override val key: String,
        val fromUser: Boolean,
        val text: String,
        val streaming: Boolean,
    ) : TimelineRow

    /** Consecutive tool calls, reasoning and file edits, summarized by their latest step. */
    data class Work(
        override val key: String,
        val steps: Int,
        val latest: String,
        val live: Boolean,
    ) : TimelineRow

    data class Attention(
        override val key: String,
        val title: String,
        val detail: String?,
        val open: Boolean,
    ) : TimelineRow

    data class Plan(override val key: String, val markdown: String) : TimelineRow

    data class Notice(override val key: String, val text: String, val isError: Boolean) : TimelineRow
}

private val WORK_TYPES = setOf(
    "reasoning",
    "command_execution",
    "file_change",
    "file_search",
    "web_search",
    "dynamic_tool",
    "subagent",
    "notification",
    "todo_list",
)

private val HIDDEN_TYPES = setOf("checkpoint", "thread_created", "fork", "handoff", "run_interrupt_result")

private val GENERIC_TITLES = setOf("Tool", "Changed files")

private val LIVE_ITEM_STATUSES = setOf("pending", "running", "waiting")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun stripEmphasis(text: String): String = text.replace("**", "").replace("__", "").trim()

private fun lastPathSegment(path: String): String = path.trimEnd('/').substringAfterLast('/')

private fun firstLine(text: String, max: Int = 80): String {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
}

/** A short label for one work step, as the phone's work log would title it. */
internal fun workLabel(item: JsonObject): String = shortenPaths(rawWorkLabel(item))

private fun rawWorkLabel(item: JsonObject): String {
    val type = item.string("type")
    // Commands and edits say more than their titles ("Tool", "Changed files"); other items' titles,
    // such as "Read package.json", say more than their type.
    if (type != "command_execution" && type != "file_change") {
        item.string("title")?.takeIf { it.isNotBlank() && it !in GENERIC_TITLES }?.let { return stripEmphasis(it) }
    }
    return when (type) {
        "reasoning" -> item.string("text")?.let { firstLine(stripEmphasis(it)) }?.ifBlank { null } ?: "Thinking"
        "command_execution" -> {
            val command = item.string("input")?.let { unwrapShell(it) } ?: ""
            val exit = item.int("exitCode")
            buildString {
                append("Ran ")
                append(firstLine(command, 60))
                if (exit != null && exit != 0) append(" (exit $exit)")
            }
        }
        "file_change" -> "Edited " + (item.string("fileName")?.let(::lastPathSegment) ?: "a file")
        "file_search" -> "Searched " + (item.string("pattern")?.let { firstLine(it, 40) } ?: "files")
        "web_search" -> "Searched the web"
        "dynamic_tool" -> "Used " + (item.string("toolName") ?: "a tool")
        "subagent" -> "Subagent: " + firstLine(item.string("progress") ?: item.string("prompt") ?: "", 60)
        "notification" -> item.string("summary")?.let { firstLine(it) } ?: "Notification"
        "todo_list" -> "Updated plan"
        else -> item.string("type") ?: "Step"
    }
}

private val SHELL_WRAPPER = Regex("""^\S*(?:zsh|bash|sh)\s+-l?c\s+(['"])(.*)\1$""", RegexOption.DOT_MATCHES_ALL)

/** `/bin/zsh -lc 'git status'` reads better as `git status`. */
internal fun unwrapShell(command: String): String =
    SHELL_WRAPPER.matchEntire(command.trim())?.groupValues?.get(2) ?: command.trim()

private fun attentionRow(key: String, item: JsonObject): TimelineRow.Attention? {
    val open = item.string("status") in LIVE_ITEM_STATUSES
    return when (item.string("type")) {
        "approval_request" -> TimelineRow.Attention(
            key = key,
            title = if (open) "Approval needed" else "Approval",
            detail = item.string("prompt") ?: item.string("reason") ?: item.string("title"),
            open = open,
        )
        "user_input_request" -> {
            val questions = (item["questions"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("question") }
            TimelineRow.Attention(
                key = key,
                title = if (open) "Question for you" else "Question",
                detail = questions?.joinToString("\n")?.ifBlank { null },
                open = open,
            )
        }
        "secret_request" -> TimelineRow.Attention(
            key = key,
            title = if (item.string("secretStatus") == "pending") "Secret requested" else "Secret request",
            detail = item.string("label"),
            open = item.string("secretStatus") == "pending",
        )
        "link_request" -> TimelineRow.Attention(
            key = key,
            title = "Link request",
            detail = item.string("reason"),
            open = item.string("linkStatus") == "pending",
        )
        else -> null
    }
}

fun buildTimeline(items: List<ProjectedTurnItem>): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>()
    var workKey: String? = null
    var workSteps = 0
    var workLatest = ""
    var workLive = false

    fun flushWork() {
        val key = workKey ?: return
        rows += TimelineRow.Work(key, workSteps, workLatest, workLive)
        workKey = null
        workSteps = 0
        workLatest = ""
        workLive = false
    }

    for (projected in items.sortedBy { it.position }) {
        val item = projected.item
        val type = item.string("type") ?: continue
        val key = item.string("id") ?: "position-${projected.position}"
        if (type in HIDDEN_TYPES) continue
        if (type in WORK_TYPES) {
            if (workKey == null) workKey = key
            workSteps += 1
            workLatest = workLabel(item)
            workLive = item.string("status") in LIVE_ITEM_STATUSES || item.bool("streaming") == true
            continue
        }
        flushWork()
        when (type) {
            "user_message", "assistant_message" -> {
                val text = item.string("text").orEmpty()
                val streaming = item.bool("streaming") == true
                if (text.isNotBlank() || streaming) {
                    rows += TimelineRow.Message(key, type == "user_message", text.trim(), streaming)
                }
            }
            "proposed_plan" -> item.string("markdown")?.takeIf { it.isNotBlank() }?.let {
                rows += TimelineRow.Plan(key, it.trim())
            }
            "error" -> {
                val message = item.obj("failure")?.string("message") ?: item.string("title") ?: "Error"
                val recovered = item.string("title")?.contains("recovered", ignoreCase = true) == true
                rows += TimelineRow.Notice(key, if (recovered) "Recovered: $message" else message, isError = !recovered)
            }
            "system_notice" -> item.string("message")?.let { rows += TimelineRow.Notice(key, it, isError = false) }
            "run_interrupt_request" -> rows += TimelineRow.Notice(key, item.string("message") ?: "Stopped", isError = false)
            "compaction" -> rows += TimelineRow.Notice(key, "Context compacted", isError = false)
            else -> attentionRow(key, item)?.let { rows += it }
        }
    }
    flushWork()
    return rows
}
