package aquapad.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Phone -> bridge over UDP at the heartbeat rate; drives the deadman. */
@Serializable
data class Heartbeat(val type: String = "hb", val seq: Long, val t: Double)

/** Phone -> bridge over WebSocket, reliable + acked. */
@Serializable
data class Command(
    val type: String,
    val seq: Long? = null,
    val t: Double? = null,
    val args: Map<String, String>? = null,
)

/** Bridge -> phone reply to a [Command]. */
@Serializable
data class Ack(
    val ack: Long? = null,
    val ok: Boolean,
    val detail: String = "",
)

/**
 * Bridge -> phone telemetry. Field names are fixed; a profile decides which are populated
 * (an omitted source stays null). [health] carries per-field freshness.
 */
@Serializable
data class TelemetryFrame(
    val t: Double,
    val seq: Long,
    val armed: Boolean,
    @SerialName("kill_latched") val killLatched: Boolean,
    @SerialName("hb_age") val hbAge: Double,
    @SerialName("mission_active") val missionActive: Boolean = false,
    val depth: Double? = null,
    val rpy: List<Double>? = null,
    @SerialName("dvl_vel") val dvlVel: List<Double>? = null,
    val batt: Double? = null,
    val health: Map<String, String> = emptyMap(),
)

/** Shared JSON codec: lenient on read, explicit on write, so the wire stays stable. */
object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(cmd: Command): String = json.encodeToString(cmd)
    fun encode(ack: Ack): String = json.encodeToString(ack)
    fun encode(hb: Heartbeat): String = json.encodeToString(hb)
    fun encode(frame: TelemetryFrame): String = json.encodeToString(frame)

    fun decodeCommand(s: String): Command = json.decodeFromString(s)
    fun decodeAck(s: String): Ack = json.decodeFromString(s)
    fun decodeHeartbeat(s: String): Heartbeat = json.decodeFromString(s)
    fun decodeFrame(s: String): TelemetryFrame = json.decodeFromString(s)
}
