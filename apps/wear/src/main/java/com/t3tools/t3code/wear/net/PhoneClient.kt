package com.t3tools.t3code.wear.net

import com.t3tools.t3code.wear.link.PhoneReply
import com.t3tools.t3code.wear.link.PhoneRequest
import com.t3tools.t3code.wear.wire.EnvironmentErrorBody
import com.t3tools.t3code.wear.wire.ProvidersPayload
import com.t3tools.t3code.wear.wire.ShellSnapshot
import com.t3tools.t3code.wear.wire.ThreadBoundedSnapshot
import com.t3tools.t3code.wear.wire.ThreadHistoryPage
import com.t3tools.t3code.wear.wire.WireJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy

sealed class PhoneException(message: String) : Exception(message) {
    /** No phone is paired and nearby. */
    class NoPhone : PhoneException("Can't reach your phone")

    /** The phone is nearby, but T3 Code there did not answer. */
    class NoAnswer : PhoneException("T3 Code on your phone isn't answering")

    /** The phone answered that it cannot reach this environment right now. */
    class NotConnected(message: String) : PhoneException(message)

    class Server(val status: Int, reason: String?) : PhoneException(reason ?: "Server error $status")
}

/**
 * Reads an environment through the phone. [send] delivers a request to the phone; the answer
 * comes back through [deliver], matched by id. Bodies are the environment's own JSON, so the
 * watch decodes the same wire format the phone received.
 */
class PhoneClient(
    private val send: suspend (ByteArray) -> Unit,
    private val timeoutMs: Long = ANSWER_TIMEOUT_MS,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PhoneReply>>()

    fun deliver(gzipped: ByteArray) {
        val reply = runCatching { WireJson.decodeFromString(PhoneReply.serializer(), gunzip(gzipped)) }.getOrNull() ?: return
        pending.remove(reply.id)?.complete(reply)
    }

    suspend fun shell(environmentId: String): ShellSnapshot =
        ask(PhoneRequest(id = newId(), environmentId = environmentId, kind = "shell"), ShellSnapshot.serializer())

    suspend fun thread(environmentId: String, threadId: String): ThreadBoundedSnapshot =
        ask(
            PhoneRequest(id = newId(), environmentId = environmentId, kind = "thread", threadId = threadId),
            ThreadBoundedSnapshot.serializer(),
        )

    suspend fun olderConversation(environmentId: String, threadId: String, cursor: String): ThreadHistoryPage =
        ask(
            PhoneRequest(id = newId(), environmentId = environmentId, kind = "history", threadId = threadId, cursor = cursor),
            ThreadHistoryPage.serializer(),
        )

    /** The phone throttles re-probing provider quotas, so this is cheap to call. */
    suspend fun providers(environmentId: String): ProvidersPayload =
        ask(PhoneRequest(id = newId(), environmentId = environmentId, kind = "usage"), ProvidersPayload.serializer())

    private suspend fun <T> ask(request: PhoneRequest, deserializer: DeserializationStrategy<T>): T {
        val answer = CompletableDeferred<PhoneReply>()
        pending[request.id] = answer
        try {
            send(WireJson.encodeToString(PhoneRequest.serializer(), request).toByteArray(Charsets.UTF_8))
            val reply = withTimeoutOrNull(timeoutMs) { answer.await() } ?: throw PhoneException.NoAnswer()
            return when (reply.status) {
                in 200..299 -> WireJson.decodeFromString(deserializer, reply.body.orEmpty())
                0 -> throw PhoneException.NotConnected(reply.error ?: "Your phone can't reach it right now")
                else -> {
                    val body = reply.body?.let {
                        runCatching { WireJson.decodeFromString(EnvironmentErrorBody.serializer(), it) }.getOrNull()
                    }
                    throw PhoneException.Server(reply.status, body?.reason ?: body?.message ?: body?._tag ?: reply.error)
                }
            }
        } finally {
            pending.remove(request.id)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        // Covers a cold phone that has to reconnect to its environment before answering.
        const val ANSWER_TIMEOUT_MS = 30_000L

        fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        fun gunzip(bytes: ByteArray): String =
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
