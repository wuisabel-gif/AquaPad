package aquapad.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import aquapad.app.input.GamepadMapper
import aquapad.app.input.PadAction
import aquapad.app.net.LinkClient
import aquapad.app.ui.HudScreen

/** Single-screen companion: captures DualSense button events and shows the telemetry HUD. */
class MainActivity : ComponentActivity() {

    private lateinit var vm: HudViewModel

    private val robotHost = "10.0.2.2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileYaml = assets.open("profiles/barracuda.yaml").bufferedReader().use { it.readText() }
        val profile = LinkClient.loadProfile(profileYaml)

        vm = ViewModelProvider(this, object : Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                HudViewModel(profile, robotHost) as T
        })[HudViewModel::class.java]

        setContent {
            val frame by vm.telemetry.collectAsStateWithLifecycle()
            val connected by vm.connected.collectAsStateWithLifecycle()
            val events by vm.events.collectAsStateWithLifecycle()
            HudScreen(
                frame = frame,
                connected = connected,
                events = events,
                onEstop = vm::estop,
                onArm = vm::toggleArm,
                onMissionStart = { vm.missionStart() },
                onMissionStop = vm::missionStop,
                onMarker = { vm.marker("ui") },
            )
        }
    }

    /** DualSense buttons -> mapped AquaPad actions. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount == 0) {
            when (GamepadMapper.actionFor(keyCode)) {
                PadAction.ESTOP -> { vm.estop(); return true }
                PadAction.ARM_TOGGLE -> { vm.toggleArm(); return true }
                PadAction.MISSION_START -> { vm.missionStart(); return true }
                PadAction.MISSION_STOP -> { vm.missionStop(); return true }
                PadAction.MARKER -> { vm.marker("pad"); return true }
                PadAction.HOLD_DEPTH -> { vm.holdDepth(); return true }
                null -> {}
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
