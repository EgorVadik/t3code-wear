package com.t3tools.t3code.wear.domain

import com.t3tools.t3code.wear.Fixtures
import com.t3tools.t3code.wear.wire.PendingBackgroundTask
import com.t3tools.t3code.wear.wire.PendingRuntimeRequest
import com.t3tools.t3code.wear.wire.ThreadLineage
import com.t3tools.t3code.wear.wire.ThreadShell
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadsTest {
    private val ran = ThreadShell(id = "t", projectId = "p", latestRunId = "run-1", status = "completed")

    @Test
    fun `status matches the phone's thread list rules`() {
        val cases = listOf(
            ran.copy(pendingRuntimeRequest = PendingRuntimeRequest("r", "command_approval")) to ThreadStatus.Approval,
            ran.copy(pendingRuntimeRequest = PendingRuntimeRequest("r", "user_input")) to ThreadStatus.Input,
            ran.copy(pendingRuntimeRequest = PendingRuntimeRequest("r", "auth_refresh")) to ThreadStatus.Ready,
            ran.copy(pendingGuestMessageCount = 2) to ThreadStatus.Request,
            ran.copy(status = "running", blockedOnThreadWait = true) to ThreadStatus.Waiting,
            ran.copy(status = "completed", activityRunStatus = "starting") to ThreadStatus.Working,
            ran.copy(status = "queued") to ThreadStatus.Working,
            ran.copy(pendingBackgroundTasks = listOf(PendingBackgroundTask("subagent"))) to ThreadStatus.Waiting,
            ran.copy(pendingBackgroundTasks = listOf(PendingBackgroundTask("command"))) to ThreadStatus.Ready,
            ran.copy(status = "failed", lastErrorClass = "usage_limit") to ThreadStatus.Limited,
            ran.copy(status = "failed", lastErrorClass = "transport_error") to ThreadStatus.Failed,
            ran.copy(latestRunId = null, status = "idle") to ThreadStatus.Ready,
        )
        for ((thread, expected) in cases) assertEquals(thread.toString(), expected, resolveStatus(thread))
    }

    @Test
    fun `a live subagent's approval surfaces on its listed parent`() {
        val rows = threadRows("env", "MacBook", Fixtures.shell).associateBy { it.threadId }

        assertEquals(setOf("thread-working", "thread-parent", "thread-limited"), rows.keys)
        assertEquals(ThreadStatus.Approval, rows.getValue("thread-parent").status)
        assertEquals(ThreadStatus.Working, rows.getValue("thread-working").status)
        assertEquals(ThreadStatus.Limited, rows.getValue("thread-limited").status)
        assertEquals("web-app", rows.getValue("thread-working").projectTitle)
        assertEquals("Running the test suite again", rows.getValue("thread-working").preview)
    }

    @Test
    fun `subagent questions stay with the coordinator but side chat questions surface`() {
        val parent = ran.copy(id = "parent")
        val subagentQuestion = ran.copy(
            id = "child",
            status = "waiting",
            activityRunStatus = "waiting",
            lineage = ThreadLineage(parentThreadId = "parent", relationshipToParent = "subagent"),
            pendingRuntimeRequest = PendingRuntimeRequest("r", "user_input"),
        )
        val sideChatQuestion = subagentQuestion.copy(
            id = "side",
            lineage = ThreadLineage(parentThreadId = "parent", sideChat = true),
        )

        assertEquals(emptyMap<String, ThreadStatus>(), rolledUpRequests(listOf(parent, subagentQuestion)))
        assertEquals(mapOf("parent" to ThreadStatus.Input), rolledUpRequests(listOf(parent, sideChatQuestion)))
    }

    @Test
    fun `needs-you and working rows lead, the rest follow by recency`() {
        val rows = threadRows("env", "MacBook", Fixtures.shell).sortedWith(ThreadRowOrder)
        assertEquals(listOf("thread-parent", "thread-working", "thread-limited"), rows.map { it.threadId })
    }
}
