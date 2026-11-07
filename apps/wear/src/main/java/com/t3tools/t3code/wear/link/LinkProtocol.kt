package com.t3tools.t3code.wear.link

import kotlinx.serialization.Serializable

/**
 * Phone ↔ watch messages over the Wearable Data Layer. The watch never connects to an environment:
 * it asks the phone, which answers over its own connection. The phone side lives in
 * apps/mobile/modules/t3-wear-bridge and apps/mobile/src/features/wear; keep both in step.
 *
 * - `/t3/environments` (phone data item): the phone's environments and whether it is connected
 *   to each, so the watch follows adds, renames and removals.
 * - `/t3/request` (watch message): one read the phone should make for the watch.
 * - `/t3/response` (phone message, or a channel when large): the answer, gzipped JSON.
 */
object LinkProtocol {
    const val PATH_ENVIRONMENTS = "/t3/environments"
    const val PATH_REQUEST = "/t3/request"
    const val PATH_RESPONSE = "/t3/response"
    const val KEY_JSON = "json"
    const val VERSION = 1
}

@Serializable
data class PhoneEnvironment(
    val environmentId: String,
    val label: String,
    val connected: Boolean = false,
)

@Serializable
data class PhoneEnvironments(
    val version: Int = LinkProtocol.VERSION,
    val environments: List<PhoneEnvironment> = emptyList(),
)

@Serializable
data class PhoneRequest(
    val version: Int = LinkProtocol.VERSION,
    val id: String,
    val environmentId: String,
    /** `shell`, `thread`, `history` or `usage`. */
    val kind: String,
    val threadId: String? = null,
    val cursor: String? = null,
)

@Serializable
data class PhoneReply(
    val version: Int = LinkProtocol.VERSION,
    val id: String,
    /** The environment's HTTP status, or 0 when the phone could not reach it. */
    val status: Int,
    val body: String? = null,
    val error: String? = null,
)
