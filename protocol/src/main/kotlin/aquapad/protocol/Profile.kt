package aquapad.protocol

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A robot profile — the only robot-specific thing in the system. Loaded from a profile YAML
 * by the app, the simulator, and the bridge alike.
 */
@Serializable
data class Profile(
    val name: String,
    val description: String = "",
    val network: Network,
    val safety: Safety,
    val telemetry: Map<String, TelemetrySpec> = emptyMap(),
    val mission: Map<String, MissionSpec> = emptyMap(),
    val setpoint: SetpointSpec? = null,
) {
    companion object {
        fun parse(yamlText: String): Profile = Yaml.default.decodeFromString(serializer(), yamlText)
    }
}

@Serializable
data class Network(
    @SerialName("udp_listen_port") val udpListenPort: Int,
    @SerialName("udp_telemetry_port") val udpTelemetryPort: Int,
    @SerialName("ws_port") val wsPort: Int,
    @SerialName("telemetry_rate_hz") val telemetryRateHz: Double = 20.0,
    @SerialName("heartbeat_expected_hz") val heartbeatExpectedHz: Double = 50.0,
)

@Serializable
data class Safety(
    val kill: Kill,
    @SerialName("heartbeat_timeout_sec") val heartbeatTimeoutSec: Double,
    @SerialName("auto_clear_kill") val autoClearKill: Boolean = false,
)

@Serializable
data class Kill(
    val topic: String,
    val type: String,
    @SerialName("transient_local") val transientLocal: Boolean = false,
)

@Serializable
data class TelemetrySpec(
    val topic: String,
    val type: String,
    val field: String? = null,
    val extract: String? = null,
    val fallback: TelemetrySpec? = null,
)

@Serializable
data class MissionSpec(
    val kind: String,
    val name: String? = null,
    val type: String? = null,
    @SerialName("accepts_plan") val acceptsPlan: Boolean = false,
)

@Serializable
data class SetpointSpec(
    val topic: String,
    val type: String,
    @SerialName("frame_id") val frameId: String? = null,
)
