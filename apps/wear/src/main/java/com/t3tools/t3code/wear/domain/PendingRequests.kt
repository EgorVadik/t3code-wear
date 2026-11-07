package com.t3tools.t3code.wear.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Something the agent is waiting on that the watch can answer through the phone. */
sealed interface PendingRequest {
    val requestId: String

    data class Approval(override val requestId: String, val options: List<ApprovalOption>) : PendingRequest

    data class Questions(override val requestId: String, val questions: List<Question>) : PendingRequest
}

data class ApprovalOption(val decision: String, val label: String)

data class Question(
    val id: String,
    val header: String,
    val question: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
    val allowCustomAnswer: Boolean,
)

/** [value] is what gets submitted: the option's value, or its label when it has none. */
data class QuestionOption(val label: String, val value: String)

/** What the user picked or said for one question. */
data class QuestionDraft(val selected: List<String> = emptyList(), val custom: String? = null)

// Matches the phone's PendingApprovalCard when the provider offers no options of its own.
private val DEFAULT_APPROVAL_OPTIONS = listOf(
    ApprovalOption("accept", "Allow once"),
    ApprovalOption("acceptForSession", "Allow session"),
    ApprovalOption("decline", "Decline"),
)

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun approvalOptions(item: JsonObject): List<ApprovalOption> =
    (item["options"] as? JsonArray)
        ?.mapNotNull { option ->
            val fields = option as? JsonObject ?: return@mapNotNull null
            val decision = fields.string("decision") ?: return@mapNotNull null
            ApprovalOption(decision, fields.string("label") ?: decision)
        }
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_APPROVAL_OPTIONS

internal fun question(fields: JsonObject): Question? {
    val id = fields.string("id") ?: return null
    return Question(
        id = id,
        header = fields.string("header").orEmpty(),
        question = fields.string("question") ?: return null,
        options = (fields["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            val optionFields = option as? JsonObject ?: return@mapNotNull null
            val label = optionFields.string("label")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            QuestionOption(label, optionFields.string("value") ?: label)
        },
        multiSelect = fields.bool("multiSelect") == true,
        allowCustomAnswer = fields.bool("allowCustomAnswer") != false,
    )
}

/**
 * Every question's answer keyed by question id, or null while any is unanswered. Mirrors
 * buildPendingUserInputAnswers in packages/client-runtime/src/state/pendingUserInput.ts.
 */
fun buildAnswers(questions: List<Question>, drafts: Map<String, QuestionDraft>): JsonObject? {
    val answers = LinkedHashMap<String, JsonElement>()
    for (question in questions) {
        val draft = drafts[question.id]
        val custom = draft?.custom?.trim()?.takeIf { it.isNotEmpty() && question.allowCustomAnswer }
        val offered = question.options.map { it.value }.toSet()
        val selected = draft?.selected.orEmpty().filter { it in offered }.distinct()
        answers[question.id] = when {
            custom != null -> JsonPrimitive(custom)
            question.multiSelect && selected.isNotEmpty() -> JsonArray(selected.map(::JsonPrimitive))
            !question.multiSelect && selected.isNotEmpty() -> JsonPrimitive(selected.first())
            else -> return null
        }
    }
    return JsonObject(answers)
}
