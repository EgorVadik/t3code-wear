package com.t3tools.t3code.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t3tools.t3code.wear.AppGraph
import com.t3tools.t3code.wear.data.describe
import com.t3tools.t3code.wear.domain.ThreadRow
import com.t3tools.t3code.wear.domain.ThreadRowOrder
import com.t3tools.t3code.wear.domain.ThreadStatus
import com.t3tools.t3code.wear.domain.TimelineRow
import com.t3tools.t3code.wear.domain.UsageCard
import com.t3tools.t3code.wear.domain.buildTimeline
import com.t3tools.t3code.wear.domain.isListed
import com.t3tools.t3code.wear.domain.resolveStatus
import com.t3tools.t3code.wear.domain.rolledUpRequests
import com.t3tools.t3code.wear.domain.threadRows
import com.t3tools.t3code.wear.domain.usageCards
import com.t3tools.t3code.wear.link.PhoneEnvironment
import com.t3tools.t3code.wear.wire.ProjectedTurnItem
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EnvironmentHealth(
    val environment: PhoneEnvironment,
    val error: String?,
    val fetchedAt: Instant?,
)

data class HomeState(
    val rows: List<ThreadRow> = emptyList(),
    val environments: List<EnvironmentHealth> = emptyList(),
    val loaded: Boolean = false,
) {
    val connectedCount: Int get() = environments.count { it.environment.connected }
    val problemCount: Int get() = environments.count { !it.environment.connected || it.error != null }
}

private const val LIVE_POLL_MS = 8_000L
private const val IDLE_POLL_MS = 20_000L

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<HomeState> = combine(graph.links.environments, graph.shells.shells) { environments, shells ->
        val rows = environments.flatMap { environment ->
            shells[environment.environmentId]?.value
                ?.let { threadRows(environment.environmentId, environment.label, it) }
                .orEmpty()
        }.sortedWith(ThreadRowOrder)
        HomeState(
            rows = rows,
            environments = environments.map { environment ->
                val fetched = shells[environment.environmentId]
                EnvironmentHealth(environment, fetched?.error, fetched?.fetchedAt)
            },
            loaded = environments.none { it.connected } || environments.any { shells[it.environmentId] != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** Collected only while the list is on screen, so the watch stays quiet in the background. */
    val polling: Flow<Unit> = graph.links.environments.flatMapLatest { environments ->
        flow {
            while (true) {
                graph.shells.refreshAll(environments)
                emit(Unit)
                val anyLive = state.value.rows.any { it.status == ThreadStatus.Working || it.status.needsYou }
                delay(if (anyLive) LIVE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    fun syncFromPhone() {
        viewModelScope.launch { graph.syncFromPhone() }
    }
}

data class ThreadState(
    val title: String = "",
    val environmentLabel: String = "",
    val status: ThreadStatus? = null,
    val rows: List<TimelineRow> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val updatedAt: Instant? = null,
)

class ThreadViewModel(
    private val graph: AppGraph,
    private val environmentId: String,
    private val threadId: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(ThreadState())
    val state: StateFlow<ThreadState> = mutable

    private var latestItems: List<ProjectedTurnItem> = emptyList()
    private var olderItems: List<ProjectedTurnItem> = emptyList()
    private var olderCursor: String? = null
    private var fetchedVersion: Pair<String, Int>? = null

    private fun environment(): PhoneEnvironment? =
        graph.links.environments.value.firstOrNull { it.environmentId == environmentId }

    val polling: Flow<Unit> = flow {
        while (true) {
            refresh()
            emit(Unit)
            val live = state.value.status == ThreadStatus.Working || state.value.status?.needsYou == true
            delay(if (live) LIVE_POLL_MS / 2 else IDLE_POLL_MS)
        }
    }

    private suspend fun refresh() {
        val environment = environment() ?: return mutable.update { it.copy(error = "Environment removed", loaded = true) }
        val shell = graph.shells.refresh(environment)
        val thread = shell?.threads?.firstOrNull { it.id == threadId }
        if (shell != null && thread != null) {
            val parentFlags = rolledUpRequests(shell.threads)
            mutable.update {
                it.copy(
                    title = thread.title,
                    environmentLabel = environment.label,
                    status = if (isListed(thread)) resolveStatus(thread, parentFlags[thread.id]) else resolveStatus(thread),
                    updatedAt = Instant.now(),
                )
            }
        }
        // The bounded snapshot is the heavy call; fetch it only when the thread actually moved.
        val version = thread?.let { it.updatedAt to it.itemCount }
        if (version != null && version == fetchedVersion) return
        try {
            val detail = graph.phone.thread(environment.environmentId, threadId)
            latestItems = detail.projection.visibleTurnItems
            if (olderItems.isEmpty()) olderCursor = detail.historyCursor.takeIf { detail.hasMoreHistory }
            fetchedVersion = version
            publishRows(error = null)
        } catch (error: Exception) {
            mutable.update { it.copy(error = describe(error), loaded = true) }
        }
    }

    fun loadOlder() {
        val cursor = olderCursor ?: return
        val environment = environment() ?: return
        if (state.value.loadingOlder) return
        mutable.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val page = graph.phone.olderConversation(environment.environmentId, threadId, cursor)
                olderItems = page.items + olderItems
                olderCursor = page.nextCursor.takeIf { page.hasMoreHistory }
                publishRows(error = null)
            } catch (error: Exception) {
                mutable.update { it.copy(error = describe(error)) }
            } finally {
                mutable.update { it.copy(loadingOlder = false) }
            }
        }
    }

    private fun publishRows(error: String?) {
        val olderRows = buildTimeline(olderItems)
        val latestRows = buildTimeline(latestItems)
        val seen = HashSet<String>()
        val rows = (olderRows + latestRows).filter { seen.add(it.key) }
        mutable.update {
            it.copy(rows = rows, hasOlder = olderCursor != null, loaded = true, error = error, updatedAt = Instant.now())
        }
    }
}

data class UsageState(
    val cards: List<UsageCard> = emptyList(),
    val refreshing: Boolean = false,
    val errors: List<String> = emptyList(),
    val loaded: Boolean = false,
)

class UsageViewModel(private val graph: AppGraph) : ViewModel() {
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<UsageState> = combine(graph.links.environments, graph.usage.usage, refreshing) { environments, usage, busy ->
        UsageState(
            cards = usageCards(graph.usage.byEnvironment(environments)),
            refreshing = busy,
            errors = environments.mapNotNull { environment ->
                usage[environment.environmentId]?.error?.let { "${environment.label}: $it" }
            },
            loaded = environments.none { it.connected } || environments.any { usage[it.environmentId] != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageState())

    val polling: Flow<Unit> = flow {
        while (true) {
            refresh()
            emit(Unit)
            delay(60_000)
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    // The phone throttles re-probing quotas, so asking again only returns what it last saw.
    private suspend fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        try {
            graph.usage.refreshAll(graph.links.environments.value)
        } finally {
            refreshing.value = false
        }
    }
}
