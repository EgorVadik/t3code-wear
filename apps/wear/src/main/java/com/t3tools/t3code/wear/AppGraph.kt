package com.t3tools.t3code.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.t3tools.t3code.wear.data.ShellRepository
import com.t3tools.t3code.wear.data.UsageRepository
import com.t3tools.t3code.wear.link.LinkProtocol
import com.t3tools.t3code.wear.link.LinkStore
import com.t3tools.t3code.wear.link.PhoneEnvironments
import com.t3tools.t3code.wear.net.PhoneClient
import com.t3tools.t3code.wear.net.PhoneException
import com.t3tools.t3code.wear.wire.WireJson
import kotlinx.coroutines.tasks.await

/** Process-wide services. The watch keeps no state of its own beyond the phone's environment list. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val links = LinkStore(appContext)
    val phone = PhoneClient(send = ::sendToPhone)
    val shells = ShellRepository(this)
    val usage = UsageRepository(this)

    private suspend fun sendToPhone(request: ByteArray) {
        val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: throw PhoneException.NoPhone()
        Wearable.getMessageClient(appContext).sendMessage(node.id, LinkProtocol.PATH_REQUEST, request).await()
    }

    fun applyPhoneEnvironmentsJson(json: String) {
        val environments = runCatching { WireJson.decodeFromString(PhoneEnvironments.serializer(), json) }
            .getOrElse {
                Log.w(TAG, "Ignoring malformed environment list", it)
                return
            }
        links.applyPhoneEnvironments(environments.environments)
    }

    /** Reads the phone's latest environment list, in case it changed while the watch was off. */
    suspend fun syncFromPhone() {
        runCatching {
            val items = Wearable.getDataClient(appContext)
                .getDataItems(Uri.parse("wear://*${LinkProtocol.PATH_ENVIRONMENTS}"))
                .await()
            try {
                items.maxByOrNull { DataMapItem.fromDataItem(it).dataMap.getLong("updatedAt") }
                    ?.let { DataMapItem.fromDataItem(it).dataMap.getString(LinkProtocol.KEY_JSON) }
                    ?.let(::applyPhoneEnvironmentsJson)
            } finally {
                items.release()
            }
        }.onFailure { Log.w(TAG, "Could not read environments from the phone", it) }
    }

    private companion object {
        const val TAG = "T3WearGraph"
    }
}
