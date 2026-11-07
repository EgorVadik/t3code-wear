package com.t3tools.t3code.wear.link

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.t3tools.t3code.wear.T3WearApplication

/** Receives the phone's environment list and its answers to the watch's requests. */
class PhoneLinkService : WearableListenerService() {
    private val graph get() = (application as T3WearApplication).graph

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != LinkProtocol.PATH_ENVIRONMENTS) continue
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(LinkProtocol.KEY_JSON) ?: continue
            graph.applyPhoneEnvironmentsJson(json)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == LinkProtocol.PATH_RESPONSE) graph.phone.deliver(event.data)
    }

    // Answers too large for one message arrive as a channel. Callbacks run on a binder worker
    // thread, so reading the stream here is fine.
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != LinkProtocol.PATH_RESPONSE) return
        val channels = Wearable.getChannelClient(this)
        runCatching { Tasks.await(channels.getInputStream(channel)).use { it.readBytes() } }
            .onSuccess(graph.phone::deliver)
            .onFailure { Log.w(TAG, "Could not read the phone's answer", it) }
        channels.close(channel)
    }

    private companion object {
        const val TAG = "T3PhoneLink"
    }
}
