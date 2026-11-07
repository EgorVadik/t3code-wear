package com.t3tools.t3code.wear

import com.t3tools.t3code.wear.wire.ProvidersPayload
import com.t3tools.t3code.wear.wire.ShellSnapshot
import com.t3tools.t3code.wear.wire.ThreadBoundedSnapshot
import com.t3tools.t3code.wear.wire.WireJson

object Fixtures {
    fun text(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    val shell: ShellSnapshot get() = WireJson.decodeFromString(ShellSnapshot.serializer(), text("shell.json"))
    val bounded: ThreadBoundedSnapshot get() = WireJson.decodeFromString(ThreadBoundedSnapshot.serializer(), text("bounded.json"))
    val providers: ProvidersPayload get() = WireJson.decodeFromString(ProvidersPayload.serializer(), text("providers.json"))
}
