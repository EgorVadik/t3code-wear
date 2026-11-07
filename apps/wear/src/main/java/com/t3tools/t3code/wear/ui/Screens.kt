package com.t3tools.t3code.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.t3tools.t3code.wear.domain.PendingRequest
import com.t3tools.t3code.wear.domain.QuestionDraft
import com.t3tools.t3code.wear.domain.ThreadRow
import com.t3tools.t3code.wear.domain.ThreadStatus
import com.t3tools.t3code.wear.domain.TimelineRow
import com.t3tools.t3code.wear.domain.UsageCard
import com.t3tools.t3code.wear.domain.buildAnswers
import com.t3tools.t3code.wear.domain.formatAgo
import com.t3tools.t3code.wear.domain.formatResetsIn
import com.t3tools.t3code.wear.domain.watchText
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Buttons, cards and headers shrink as they near the round edge instead of being clipped by it.
// Plain text is left unscaled: scaling a tall block breaks the spacing between neighbouring items.

private fun plain(): Modifier = Modifier.fillMaxWidth()

private fun TransformingLazyColumnItemScope.surface(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth().transformedHeight(this, spec)

@Composable
private fun TransformingLazyColumnItemScope.Header(spec: TransformationSpec, text: String) {
    ListHeader(modifier = surface(spec), transformation = SurfaceTransformation(spec)) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
fun HomeScreen(
    model: HomeViewModel,
    onOpenThread: (ThreadRow) -> Unit,
    onOpenUsage: () -> Unit,
    onOpenEnvironments: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    HomeContent(state, onOpenThread, onOpenUsage, onOpenEnvironments, onSync = model::syncFromPhone)
}

@Composable
fun HomeContent(
    state: HomeState,
    onOpenThread: (ThreadRow) -> Unit,
    onOpenUsage: () -> Unit,
    onOpenEnvironments: () -> Unit,
    onSync: () -> Unit,
    now: Instant = Instant.now(),
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val waitingForPhone = state.environments.isEmpty() || state.connectedCount == 0
    ScreenScaffold(
        scrollState = listState,
        // The last action sits in the edge button, the one shape that fits the bottom of a round screen.
        edgeButton = {
            if (waitingForPhone) {
                EdgeButton(onClick = onSync, buttonSize = EdgeButtonSize.Small) { Text("Check again") }
            } else {
                EdgeButton(onClick = onOpenEnvironments, buttonSize = EdgeButtonSize.Large) { Text("Environments", maxLines = 1, softWrap = false) }
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { Header(spec, "T3 Code") }
            when {
                state.environments.isEmpty() -> item {
                    Hint(spec, "Open T3 Code on your phone to bring your environments here.")
                }
                state.connectedCount == 0 -> item {
                    Hint(spec, "Your phone isn't connected to any environment right now.")
                }
                else -> {
                    item {
                        Button(
                            onClick = onOpenUsage,
                            modifier = surface(spec),
                            transformation = SurfaceTransformation(spec),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            label = { Text("Usage limits") },
                        )
                    }
                    when {
                        !state.loaded -> item { Loading(spec) }
                        state.rows.isEmpty() -> item { Hint(spec, state.environments.firstNotNullOfOrNull { it.error } ?: "No active threads") }
                        else -> threadSections(spec, state.rows, onOpenThread, now)
                    }
                    item {
                        Hint(
                            spec,
                            when {
                                state.problemCount == 1 -> "1 environment needs attention"
                                state.problemCount > 1 -> "${state.problemCount} environments need attention"
                                state.connectedCount == 1 -> "1 environment connected"
                                else -> "${state.connectedCount} environments connected"
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun TransformingLazyColumnScope.threadSections(
    spec: TransformationSpec,
    rows: List<ThreadRow>,
    onOpenThread: (ThreadRow) -> Unit,
    now: Instant,
) {
    val needsYou = rows.filter { it.status.needsYou }
    val working = rows.filter { it.status == ThreadStatus.Working }
    val rest = rows.filter { !it.status.needsYou && it.status != ThreadStatus.Working }.take(RECENT_LIMIT)
    val showEnvironment = rows.map { it.environmentId }.distinct().size > 1
    for ((title, section) in listOf("Needs you" to needsYou, "Working" to working, "Recent" to rest)) {
        if (section.isEmpty()) continue
        item(key = "header-$title") { Header(spec, title) }
        items(section.size, key = { "${section[it].environmentId}/${section[it].threadId}" }) { index ->
            ThreadCard(spec, section[index], showEnvironment, now) { onOpenThread(section[index]) }
        }
    }
}

private const val RECENT_LIMIT = 25

@Composable
private fun TransformingLazyColumnItemScope.ThreadCard(
    spec: TransformationSpec,
    row: ThreadRow,
    showEnvironment: Boolean,
    now: Instant,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = surface(spec), transformation = SurfaceTransformation(spec)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(row.status.color())
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.status.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = row.status.color(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatAgo(row.updatedAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val context = listOfNotNull(row.projectTitle, row.environmentLabel.takeIf { showEnvironment }).joinToString(" · ")
        if (context.isNotEmpty()) {
            Text(
                text = context,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        row.preview?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ThreadScreen(model: ThreadViewModel, onReply: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    ThreadContent(
        state,
        onLoadOlder = model::loadOlder,
        onReply = onReply,
        onApprove = model::respondToApproval,
        onAnswer = model::answerQuestions,
    )
}

@Composable
fun ThreadContent(
    state: ThreadState,
    onLoadOlder: () -> Unit,
    onReply: () -> Unit,
    onApprove: (requestId: String, decision: String) -> Unit,
    onAnswer: (requestId: String, answers: JsonObject) -> Unit,
    now: Instant = Instant.now(),
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var scrolledToLatest by remember { mutableStateOf(false) }
    // Answers picked so far, by request id and then question id.
    var drafts by remember { mutableStateOf(mapOf<String, Map<String, QuestionDraft>>()) }
    fun updateDraft(requestId: String, questionId: String, change: (QuestionDraft) -> QuestionDraft) {
        val forRequest = drafts[requestId].orEmpty()
        drafts = drafts + (requestId to forRequest + (questionId to change(forRequest[questionId] ?: QuestionDraft())))
    }
    var speakingFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val speak = rememberVoiceInput("Your answer") { text ->
        speakingFor?.let { (requestId, questionId) -> updateDraft(requestId, questionId) { it.copy(custom = text) } }
    }
    // Like a chat: open at the newest message and keep following new ones, which also keeps the
    // Reply button in view. Scrolling up to read stops following until the end is reached again.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && scrolledToLatest) following = !listState.canScrollForward
        }
    }
    LaunchedEffect(state.loaded, state.rows) {
        if (!state.loaded || state.rows.isEmpty()) return@LaunchedEffect
        if (!scrolledToLatest) {
            listState.scrollToItem(state.rows.size + LEADING_ITEMS)
            scrolledToLatest = true
        } else if (!following) {
            return@LaunchedEffect
        }
        // Settle at the very end, where the Reply edge button shows. Items measure lazily, so one
        // scroll can stop short of it.
        repeat(MAX_SCROLL_TO_END_PASSES) {
            listState.scrollBy(Float.MAX_VALUE)
            withFrameNanos {}
            if (!listState.canScrollForward) return@LaunchedEffect
        }
    }
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (state.loaded) {
                EdgeButton(onClick = onReply, buttonSize = EdgeButtonSize.Small) { Text("Reply") }
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                Column(plain().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.title.ifBlank { "Thread" },
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.status?.let { status ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(status.color())
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = listOf(status.label, state.environmentLabel).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item {
                when {
                    state.loadingOlder -> Loading(spec)
                    state.hasOlder -> Button(
                        onClick = onLoadOlder,
                        modifier = surface(spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        label = { Text("Earlier messages") },
                    )
                    else -> Spacer(Modifier)
                }
            }
            if (!state.loaded) {
                item { Loading(spec) }
            } else if (state.rows.isEmpty()) {
                item { Hint(spec, state.error ?: "Nothing here yet") }
            } else {
                for (row in state.rows) {
                    item(key = row.key) { TimelineItem(spec, row) }
                    val request = (row as? TimelineRow.Attention)?.request ?: continue
                    requestItems(
                        spec = spec,
                        request = request,
                        responding = state.responding == request.requestId,
                        enabled = state.responding == null,
                        drafts = drafts[request.requestId].orEmpty(),
                        onApprove = { decision -> onApprove(request.requestId, decision) },
                        onToggle = { questionId, value ->
                            updateDraft(request.requestId, questionId) { draft ->
                                val multi = (request as PendingRequest.Questions).questions.first { it.id == questionId }.multiSelect
                                val selected = when {
                                    !multi -> listOf(value)
                                    value in draft.selected -> draft.selected - value
                                    else -> draft.selected + value
                                }
                                QuestionDraft(selected = selected)
                            }
                        },
                        onSpeak = { questionId ->
                            speakingFor = request.requestId to questionId
                            speak()
                        },
                        onSubmit = { answers -> onAnswer(request.requestId, answers) },
                    )
                }
                state.actionError?.let { error -> item { Hint(spec, error) } }
                item { Hint(spec, state.error ?: state.updatedAt?.let { "Updated ${formatAgo(it, now)}" } ?: "") }
            }
        }
    }
}

private const val LEADING_ITEMS = 2
private const val MAX_SCROLL_TO_END_PASSES = 5

@Composable
private fun TransformingLazyColumnItemScope.TimelineItem(spec: TransformationSpec, row: TimelineRow) {
    when (row) {
        is TimelineRow.Message -> if (row.fromUser) {
            Column(
                plain()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(watchText(row.text), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                text = watchText(row.text).ifBlank { "…" } + if (row.streaming) " ▍" else "",
                modifier = plain().padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is TimelineRow.Work -> Row(
            plain().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (row.live) StatusColors.working else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(6.dp))
            Text(
                text = (if (row.steps > 1) "${row.steps} steps · " else "") + row.latest,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is TimelineRow.Attention -> Card(
            onClick = {},
            enabled = false,
            modifier = surface(spec),
            transformation = SurfaceTransformation(spec),
            colors = if (row.open) {
                CardDefaults.cardColors(containerColor = Color(0xFF3A2A10))
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (row.open) StatusColors.attention else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.detail?.let { Text(watchText(it), style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis) }
            if (row.open && row.request == null) {
                Text(
                    "Answer from your phone or desktop",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is TimelineRow.Plan -> InfoCard(spec) {
            Text("Plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(watchText(row.markdown), style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
        }
        is TimelineRow.Notice -> Text(
            text = row.text,
            modifier = plain().padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Answer buttons under an open approval or question; each is its own item so it shrinks at the edge. */
private fun TransformingLazyColumnScope.requestItems(
    spec: TransformationSpec,
    request: PendingRequest,
    responding: Boolean,
    enabled: Boolean,
    drafts: Map<String, QuestionDraft>,
    onApprove: (decision: String) -> Unit,
    onToggle: (questionId: String, value: String) -> Unit,
    onSpeak: (questionId: String) -> Unit,
    onSubmit: (JsonObject) -> Unit,
) {
    val key = request.requestId
    if (responding) {
        item(key = "$key-busy") { Loading(spec) }
        return
    }
    when (request) {
        is PendingRequest.Approval -> for (option in request.options) {
            val declines = option.decision == "decline" || option.decision == "cancel"
            item(key = "$key-${option.decision}") {
                Button(
                    onClick = { onApprove(option.decision) },
                    modifier = surface(spec),
                    enabled = enabled,
                    transformation = SurfaceTransformation(spec),
                    colors = if (declines) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.filledTonalButtonColors(),
                    border = if (declines) ButtonDefaults.outlinedButtonBorder(enabled = enabled) else null,
                    label = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        is PendingRequest.Questions -> {
            // One single-choice question answers with one tap; anything else is picked, then submitted.
            val oneTap = request.questions.size == 1 && !request.questions[0].multiSelect
            for (question in request.questions) {
                val draft = drafts[question.id]
                val spoken = draft?.custom?.takeIf { it.isNotBlank() }
                if (request.questions.size > 1) {
                    item(key = "$key-${question.id}-ask") { Hint(spec, question.question) }
                }
                for (option in question.options) {
                    val selected = spoken == null && option.value in draft?.selected.orEmpty()
                    item(key = "$key-${question.id}-${option.value}") {
                        Button(
                            onClick = {
                                if (oneTap) {
                                    onSubmit(JsonObject(mapOf(question.id to JsonPrimitive(option.value))))
                                } else {
                                    onToggle(question.id, option.value)
                                }
                            },
                            modifier = surface(spec),
                            enabled = enabled,
                            transformation = SurfaceTransformation(spec),
                            colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                            label = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                if (question.allowCustomAnswer) {
                    item(key = "$key-${question.id}-voice") {
                        Button(
                            onClick = { onSpeak(question.id) },
                            modifier = surface(spec),
                            enabled = enabled,
                            transformation = SurfaceTransformation(spec),
                            colors = ButtonDefaults.outlinedButtonColors(),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
                            label = { Text(spoken?.let { "“$it”" } ?: "Answer by voice", maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            val answers = buildAnswers(request.questions, drafts)
            if (!oneTap || answers != null) {
                item(key = "$key-submit") {
                    Button(
                        onClick = { answers?.let(onSubmit) },
                        modifier = surface(spec),
                        enabled = enabled && answers != null,
                        transformation = SurfaceTransformation(spec),
                        label = { Text("Submit") },
                    )
                }
            }
        }
    }
}

@Composable
fun ReplyScreen(model: ReplyViewModel, onSent: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sent) { if (state.sent) onSent() }
    ReplyContent(state, onText = model::setText, onSend = model::send)
}

/** Dictate, read it back, then send: a misheard reply never reaches the agent unchecked. */
@Composable
fun ReplyContent(state: ReplyState, onText: (String) -> Unit, onSend: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val speak = rememberVoiceInput("Reply", onText)
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!askedOnce && state.text.isEmpty()) {
            askedOnce = true
            speak()
        }
    }
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = onSend,
                buttonSize = EdgeButtonSize.Medium,
                enabled = state.text.isNotBlank() && !state.sending,
            ) { Text(if (state.sending) "Sending…" else "Send") }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { Header(spec, "Reply") }
            item {
                if (state.text.isBlank()) {
                    Hint(spec, "Tap Speak and say your reply.")
                } else {
                    InfoCard(spec) { Text(state.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                Button(
                    onClick = speak,
                    modifier = surface(spec),
                    enabled = !state.sending,
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = !state.sending),
                    label = { Text(if (state.text.isBlank()) "Speak" else "Speak again") },
                )
            }
            state.error?.let { error -> item { Hint(spec, error) } }
        }
    }
}

@Composable
fun UsageScreen(model: UsageViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    UsageContent(state, onRefresh = model::refreshNow)
}

@Composable
fun UsageContent(state: UsageState, onRefresh: () -> Unit, now: Instant = Instant.now()) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onRefresh, buttonSize = EdgeButtonSize.Small, enabled = !state.refreshing) {
                Text(if (state.refreshing) "Checking…" else "Refresh")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { Header(spec, "Usage") }
            when {
                !state.loaded -> item { Loading(spec) }
                state.cards.isEmpty() -> item { Hint(spec, state.errors.firstOrNull() ?: "No provider reports limits") }
                else -> {
                    items(state.cards.size, key = { state.cards[it].key }) { index -> UsageCardView(spec, state.cards[index], now) }
                    state.errors.forEach { error -> item { Hint(spec, error) } }
                }
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.UsageCardView(spec: TransformationSpec, card: UsageCard, now: Instant) {
    InfoCard(spec) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.providerName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatAgo(card.checkedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.problem?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            for (window in card.windows) {
                Column {
                    Row {
                        Text(
                            window.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${window.remainingPercent}% left",
                            style = MaterialTheme.typography.labelMedium,
                            color = remainingColor(window.remainingPercent),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { window.remainingPercent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = ProgressIndicatorDefaults.colors(
                            indicatorColor = remainingColor(window.remainingPercent),
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                    formatResetsIn(window.resetsAt, now)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (card.environmentLabels.size > 1) {
                Text(
                    "Shared by ${card.environmentLabels.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun EnvironmentsScreen(model: HomeViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    EnvironmentsContent(state, onSync = model::syncFromPhone)
}

@Composable
fun EnvironmentsContent(state: HomeState, onSync: () -> Unit, now: Instant = Instant.now()) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onSync, buttonSize = EdgeButtonSize.Small) { Text("Sync") }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { Header(spec, "Environments") }
            if (state.environments.isEmpty()) {
                item { Hint(spec, "None yet. Open T3 Code on your phone; the watch shows every environment it has.") }
            }
            items(state.environments.size, key = { state.environments[it].environment.environmentId }) { index ->
                val health = state.environments[index]
                val environment = health.environment
                val (color, detail) = when {
                    !environment.connected -> StatusColors.attention to "Not connected on your phone"
                    health.error != null -> StatusColors.failed to health.error
                    health.fetchedAt != null -> StatusColors.healthy to "Updated ${formatAgo(health.fetchedAt, now)}"
                    else -> StatusColors.ready to "Connected on your phone"
                }
                InfoCard(spec) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color)
                        Spacer(Modifier.width(6.dp))
                        Text(environment.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A read-only card. */
@Composable
private fun TransformingLazyColumnItemScope.InfoCard(spec: TransformationSpec, content: @Composable ColumnScope.() -> Unit) {
    Card(
        onClick = {},
        enabled = false,
        modifier = surface(spec),
        transformation = SurfaceTransformation(spec),
        content = content,
    )
}

@Composable
private fun TransformingLazyColumnItemScope.Loading(spec: TransformationSpec) {
    Row(plain().padding(8.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun TransformingLazyColumnItemScope.Hint(spec: TransformationSpec, text: String) {
    Text(
        text = text,
        modifier = plain().padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
