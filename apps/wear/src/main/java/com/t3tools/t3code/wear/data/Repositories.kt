package com.t3tools.t3code.wear.data

import com.t3tools.t3code.wear.AppGraph
import com.t3tools.t3code.wear.domain.EnvironmentProviders
import com.t3tools.t3code.wear.link.PhoneEnvironment
import com.t3tools.t3code.wear.wire.ServerProvider
import com.t3tools.t3code.wear.wire.ShellSnapshot
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Fetched<T>(
    val value: T?,
    val fetchedAt: Instant?,
    val error: String?,
)

fun describe(error: Throwable): String = error.message ?: "Something went wrong"

private fun <T> MutableStateFlow<Map<String, Fetched<T>>>.record(environmentId: String, result: Result<T>) = update { current ->
    val previous = current[environmentId]
    current + (
        environmentId to result.fold(
            onSuccess = { Fetched(it, Instant.now(), null) },
            onFailure = { Fetched(previous?.value, previous?.fetchedAt, describe(it)) },
        )
        )
}

/** Latest thread list per environment, shared by the list and thread screens. */
class ShellRepository(private val graph: AppGraph) {
    private val state = MutableStateFlow<Map<String, Fetched<ShellSnapshot>>>(emptyMap())
    val shells: StateFlow<Map<String, Fetched<ShellSnapshot>>> = state.asStateFlow()

    suspend fun refreshAll(environments: List<PhoneEnvironment>) = coroutineScope {
        environments.filter { it.connected }.map { async { refresh(it) } }.awaitAll()
        val known = environments.map { it.environmentId }.toSet()
        state.update { current -> current.filterKeys { it in known } }
    }

    suspend fun refresh(environment: PhoneEnvironment): ShellSnapshot? {
        val result = runCatching { graph.phone.shell(environment.environmentId) }
        state.record(environment.environmentId, result)
        return result.getOrNull()
    }
}

/** Provider quotas per environment, as the phone last saw them. */
class UsageRepository(private val graph: AppGraph) {
    private val state = MutableStateFlow<Map<String, Fetched<List<ServerProvider>>>>(emptyMap())
    val usage: StateFlow<Map<String, Fetched<List<ServerProvider>>>> = state.asStateFlow()

    suspend fun refreshAll(environments: List<PhoneEnvironment>) = coroutineScope {
        environments.filter { it.connected }
            .map { environment ->
                async {
                    val result = runCatching { graph.phone.providers(environment.environmentId).providers }
                    state.record(environment.environmentId, result)
                }
            }
            .awaitAll()
    }

    fun byEnvironment(environments: List<PhoneEnvironment>): List<EnvironmentProviders> =
        environments.mapNotNull { environment ->
            state.value[environment.environmentId]?.value?.let {
                EnvironmentProviders(environment.environmentId, environment.label, it)
            }
        }
}
