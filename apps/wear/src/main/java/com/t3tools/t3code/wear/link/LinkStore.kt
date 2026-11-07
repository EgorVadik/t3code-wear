package com.t3tools.t3code.wear.link

import android.content.Context
import android.content.SharedPreferences
import com.t3tools.t3code.wear.wire.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/** The phone's environments, kept so the watch can show them before the phone answers. */
class LinkStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("t3-links", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(PhoneEnvironment.serializer())
    private val state = MutableStateFlow(load())

    val environments: StateFlow<List<PhoneEnvironment>> = state.asStateFlow()

    /** The phone's list is the source of truth: environments it dropped are forgotten here. */
    @Synchronized
    fun applyPhoneEnvironments(phone: List<PhoneEnvironment>) {
        prefs.edit().putString(KEY, WireJson.encodeToString(serializer, phone)).apply()
        state.value = phone
    }

    private fun load(): List<PhoneEnvironment> =
        prefs.getString(KEY, null)
            ?.let { runCatching { WireJson.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        // v1 held per-environment sessions from when the watch connected on its own.
        const val KEY = "environments.v2"
    }
}
