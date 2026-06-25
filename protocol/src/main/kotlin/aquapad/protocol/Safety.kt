package aquapad.protocol

/**
 * Pure safety state machine shared by the app, simulator, and (mirrored) bridge.
 *
 * [armed] is operator intent; [killLatched] is the actual kill output. A deadman latch
 * (heartbeat loss) keeps intent so [autoClearKill] can re-energize when beats return;
 * `estop`/`disarm` latches are sticky and never auto-clear. Boots un-latched, but the first
 * [tick] with no heartbeat latches, so a robot with no phone is killed until armed.
 */
class SafetyState(
    private val heartbeatTimeoutSec: Double,
    private val autoClearKill: Boolean = false,
) {
    var armed: Boolean = false
        private set
    var killLatched: Boolean = false
        private set
    var latchReason: String? = null
        private set

    /** Advance the deadman; [hbAgeSec] is seconds since the last heartbeat. */
    fun tick(hbAgeSec: Double): Boolean {
        if (hbAgeSec > heartbeatTimeoutSec) {
            if (!killLatched) {
                killLatched = true
                latchReason = "deadman"
            }
        } else if (killLatched && latchReason == "deadman" && autoClearKill && armed) {
            killLatched = false
            latchReason = null
        }
        return killLatched
    }

    fun apply(type: String): Pair<Boolean, String> = when (type) {
        "estop", "disarm" -> {
            killLatched = true
            armed = false
            latchReason = type
            true to type
        }
        "arm" -> {
            killLatched = false
            armed = true
            latchReason = null
            true to "armed"
        }
        else -> if (killLatched) false to "rejected: kill latched, send 'arm'" else true to type
    }
}
