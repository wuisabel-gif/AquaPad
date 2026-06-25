package aquapad.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import aquapad.app.net.LinkClient
import aquapad.protocol.Profile
import aquapad.protocol.TelemetryFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the link and exposes telemetry + a short event log; survives rotation. */
class HudViewModel(
    val profile: Profile,
    host: String,
) : ViewModel() {

    private val link = LinkClient(profile, host, viewModelScope)

    val telemetry: StateFlow<TelemetryFrame?> get() = link.telemetry
    val connected: StateFlow<Boolean> get() = link.connected

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    private var missionArmed = false

    init {
        link.onAckReceived { ack -> push("ack#${ack.ack} ${if (ack.ok) "ok" else "FAIL"} ${ack.detail}") }
        link.start()
    }

    fun estop() { link.estop(); push("E-STOP sent") }
    fun toggleArm() {
        if (missionArmed) link.disarm() else link.arm()
        missionArmed = !missionArmed
    }
    fun missionStart(plan: String = "default") { link.missionStart(plan) }
    fun missionStop() { link.missionStop() }
    fun marker(label: String = "") { link.marker(label) }
    fun holdDepth() { link.send("hold_depth") }

    private fun push(line: String) {
        _events.value = (listOf(line) + _events.value).take(8)
    }

    override fun onCleared() {
        link.stop()
        super.onCleared()
    }
}
