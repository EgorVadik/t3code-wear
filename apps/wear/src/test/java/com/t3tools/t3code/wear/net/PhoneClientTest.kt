package com.t3tools.t3code.wear.net

import com.t3tools.t3code.wear.Fixtures
import com.t3tools.t3code.wear.link.PhoneReply
import com.t3tools.t3code.wear.link.PhoneRequest
import com.t3tools.t3code.wear.wire.WireJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PhoneClientTest {
    private val sent = mutableListOf<PhoneRequest>()

    /** A phone that answers every request with [reply]. */
    private fun phone(reply: (PhoneRequest) -> PhoneReply?): PhoneClient {
        lateinit var client: PhoneClient
        client = PhoneClient(send = { bytes ->
            val request = WireJson.decodeFromString(PhoneRequest.serializer(), bytes.toString(Charsets.UTF_8))
            sent += request
            reply(request)?.let { client.deliver(PhoneClient.gzip(WireJson.encodeToString(PhoneReply.serializer(), it))) }
        })
        return client
    }

    @Test
    fun `the phone's answer is decoded as the environment's own wire format`() = runTest {
        val client = phone { PhoneReply(id = it.id, status = 200, body = Fixtures.text("shell.json")) }

        val shell = client.shell("env-1")

        assertEquals(listOf(PhoneRequest(id = sent[0].id, environmentId = "env-1", kind = "shell")), sent)
        assertEquals(5, shell.threads.size)
    }

    @Test
    fun `a phone that cannot reach the environment says so`() = runTest {
        val client = phone { PhoneReply(id = it.id, status = 0, error = "Your phone is not connected to it right now") }
        try {
            client.thread("env-1", "thread-1")
            fail("expected NotConnected")
        } catch (error: PhoneException.NotConnected) {
            assertEquals("Your phone is not connected to it right now", error.message)
        }
    }

    @Test
    fun `an environment error keeps its reason`() = runTest {
        val client = phone {
            PhoneReply(id = it.id, status = 404, body = """{"_tag":"EnvironmentResourceNotFoundError","reason":"thread_not_found"}""")
        }
        try {
            client.olderConversation("env-1", "thread-1", "cursor-1")
            fail("expected Server")
        } catch (error: PhoneException.Server) {
            assertEquals(404, error.status)
            assertEquals("thread_not_found", error.message)
        }
    }

    @Test
    fun `a silent phone times out instead of hanging the screen`() = runTest {
        val client = phone { null }
        try {
            client.providers("env-1")
            fail("expected NoAnswer")
        } catch (_: PhoneException.NoAnswer) {
        }
        assertEquals("usage", sent.single().kind)
    }
}
