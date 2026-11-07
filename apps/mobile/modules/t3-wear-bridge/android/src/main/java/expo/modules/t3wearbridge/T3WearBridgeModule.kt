package expo.modules.t3wearbridge

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The phone end of the T3 Code watch link: the watch asks, this app answers over its own
 * connection. Paths and payloads mirror apps/wear/src/main/java/com/t3tools/t3code/wear/link/LinkProtocol.kt.
 */
class T3WearBridgeModule : Module() {
  private val context: Context
    get() = requireNotNull(appContext.reactContext) { "React context is not available" }

  private val requestListener = MessageClient.OnMessageReceivedListener { event ->
    if (event.path == PATH_REQUEST) {
      sendEvent(
        EVENT_WATCH_REQUEST,
        mapOf("nodeId" to event.sourceNodeId, "json" to event.data.toString(Charsets.UTF_8)),
      )
    }
  }

  private var keepAliveTaskId: Int? = null

  override fun definition() = ModuleDefinition {
    Name("T3WearBridge")
    Events(EVENT_WATCH_REQUEST)

    OnStartObserving {
      Wearable.getMessageClient(context).addListener(requestListener)
    }

    OnStopObserving {
      Wearable.getMessageClient(context).removeListener(requestListener)
    }

    /** Paired watches that have the T3 Code watch app installed and are reachable now. */
    AsyncFunction("watchNodeIds") {
      Tasks.await(
        Wearable.getCapabilityClient(context).getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE),
      ).nodes.map { it.id }
    }

    AsyncFunction("publishEnvironments") { json: String ->
      val request = PutDataMapRequest.create(PATH_ENVIRONMENTS).apply {
        dataMap.putString(KEY_JSON, json)
        dataMap.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
      }.asPutDataRequest().setUrgent()
      Tasks.await(Wearable.getDataClient(context).putDataItem(request))
      Unit
    }

    // Thread snapshots outgrow a Data Layer message, so large answers stream over a channel.
    AsyncFunction("respond") { nodeId: String, json: String ->
      val bytes = gzip(json)
      if (bytes.size <= MAX_MESSAGE_BYTES) {
        Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, PATH_RESPONSE, bytes))
      } else {
        val channels = Wearable.getChannelClient(context)
        val channel = Tasks.await(channels.openChannel(nodeId, PATH_RESPONSE))
        Tasks.await(channels.getOutputStream(channel)).use { it.write(bytes) }
      }
      Unit
    }

    /**
     * Keeps this app answering the watch while it is in the background: a foreground service
     * holds the process, and a headless JS task keeps React Native's timers running.
     */
    Function("startKeepAlive") {
      T3WearSyncService.start(context)
      val reactContext = appContext.reactContext as? ReactContext
      if (keepAliveTaskId == null && reactContext != null) {
        keepAliveTaskId = HeadlessJsTaskContext.getInstance(reactContext).startTask(
          HeadlessJsTaskConfig(KEEP_ALIVE_TASK, Arguments.createMap(), 0, true),
        )
      }
    }

    Function("stopKeepAlive") {
      val taskId = keepAliveTaskId
      val reactContext = appContext.reactContext as? ReactContext
      if (taskId != null && reactContext != null) {
        HeadlessJsTaskContext.getInstance(reactContext).finishTask(taskId)
      }
      keepAliveTaskId = null
      T3WearSyncService.stop(context)
    }
  }

  private fun gzip(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    return out.toByteArray()
  }

  private companion object {
    const val WATCH_CAPABILITY = "t3_watch"
    const val PATH_ENVIRONMENTS = "/t3/environments"
    const val PATH_REQUEST = "/t3/request"
    const val PATH_RESPONSE = "/t3/response"
    const val KEY_JSON = "json"
    const val KEY_UPDATED_AT = "updatedAt"
    const val EVENT_WATCH_REQUEST = "onWatchRequest"
    const val KEEP_ALIVE_TASK = "T3WearKeepAlive"
    // The Data Layer caps a message near 100 KB.
    const val MAX_MESSAGE_BYTES = 90_000
  }
}
