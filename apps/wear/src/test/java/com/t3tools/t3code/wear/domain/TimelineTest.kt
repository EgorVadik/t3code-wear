package com.t3tools.t3code.wear.domain

import com.t3tools.t3code.wear.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    @Test
    fun `conversation keeps messages and folds the work between them`() {
        val rows = buildTimeline(Fixtures.bounded.projection.visibleTurnItems)

        assertEquals(
            listOf(
                TimelineRow.Message("i-1", fromUser = true, text = "Why does the login test fail on CI?", streaming = false),
                TimelineRow.Work("i-2", steps = 2, latest = "Ran pnpm test login (exit 1)", live = false),
                TimelineRow.Message("i-5", fromUser = false, text = "## Cause\nThe test races the **session cookie**.", streaming = false),
                TimelineRow.Attention(
                    "i-6",
                    title = "Approval needed",
                    detail = "Allow running pnpm db:migrate?",
                    open = true,
                    request = PendingRequest.Approval(
                        "r-1",
                        listOf(
                            ApprovalOption("accept", "Allow once"),
                            ApprovalOption("acceptForSession", "Allow session"),
                            ApprovalOption("decline", "Decline"),
                        ),
                    ),
                ),
                TimelineRow.Work("i-7", steps = 1, latest = "Edited login.test.ts", live = true),
                TimelineRow.Message("i-9", fromUser = false, text = "Fixing it now", streaming = true),
            ),
            rows,
        )
    }

    @Test
    fun `answers are submitted only once every question has one`() {
        val pick = Question("q1", "Scope", "Which tests?", listOf(QuestionOption("Unit", "unit"), QuestionOption("E2E", "E2E")), multiSelect = false, allowCustomAnswer = true)
        val many = Question("q2", "Envs", "Where?", listOf(QuestionOption("Staging", "staging"), QuestionOption("Prod", "prod")), multiSelect = true, allowCustomAnswer = false)
        val questions = listOf(pick, many)

        assertEquals(null, buildAnswers(questions, mapOf("q1" to QuestionDraft(selected = listOf("unit")))))
        assertEquals(
            """{"q1":"unit","q2":["prod","staging"]}""",
            buildAnswers(questions, mapOf("q1" to QuestionDraft(selected = listOf("unit")), "q2" to QuestionDraft(selected = listOf("prod", "staging", "nope")))).toString(),
        )
        // A spoken answer wins over a selection; a question that takes no custom answer ignores it.
        assertEquals(
            """{"q1":"only the login tests","q2":["prod"]}""",
            buildAnswers(questions, mapOf("q1" to QuestionDraft(listOf("unit"), "only the login tests "), "q2" to QuestionDraft(listOf("prod"), "everywhere"))).toString(),
        )
    }

    @Test
    fun `shell wrappers are dropped from command labels`() {
        assertEquals("git status", unwrapShell("/bin/zsh -lc 'git status'"))
        assertEquals("npm test", unwrapShell("bash -c \"npm test\""))
        assertEquals("ls -la", unwrapShell("ls -la"))
    }

    @Test
    fun `markdown is flattened to plain watch text`() {
        val markdown = """
            ## Result
            I added `clamp` next to **sum**, see [the PR](https://example.com/pr/9).

            - Reads the manifest first
            | Step | Tool |
            | --- | --- |
            | Read | `read` |
            ```ts
            clamp(12, 0, 10);
            ```
        """.trimIndent()

        assertEquals(
            "Result\nI added clamp next to sum, see the PR.\n\n• Reads the manifest first\nStep · Tool\nRead · read\nclamp(12, 0, 10);",
            watchText(markdown),
        )
        assertEquals("Read login.ts and README.md", shortenPaths("Read /Users/me/app/src/login.ts and /tmp/x/README.md"))
    }

    @Test
    fun `work labels prefer what was done over generic titles`() {
        fun label(json: String) = workLabel(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
        assertEquals("Ran vp test run", label("""{"type":"command_execution","title":"Tool","input":"vp test run"}"""))
        assertEquals("Edited package.json", label("""{"type":"file_change","title":"Changed files","fileName":"/w/app/package.json"}"""))
        assertEquals("Read package.json", label("""{"type":"dynamic_tool","title":"Read /w/app/package.json","toolName":"Read"}"""))
        assertEquals("Used link_pull_request", label("""{"type":"dynamic_tool","title":null,"toolName":"link_pull_request"}"""))
    }
}
