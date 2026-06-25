import aquapad.protocol.SafetyState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyStateTest {
    private fun fresh() = SafetyState(heartbeatTimeoutSec = 0.75, autoClearKill = false)

    @Test fun bootsFailSafe() {
        val s = fresh()
        assertFalse(s.killLatched, "boots un-latched")
        s.tick(hbAgeSec = 1e9)
        assertTrue(s.killLatched, "stale heartbeat latches kill (fail-safe boot)")
    }

    @Test fun armClearsAndHolds() {
        val s = fresh()
        s.tick(1e9)
        assertTrue(s.apply("arm").first)
        assertFalse(s.killLatched, "arm clears the latch")
        assertTrue(s.armed)
        s.tick(0.02)
        assertFalse(s.killLatched, "fresh heartbeat keeps kill clear")
    }

    @Test fun estopLatchesAndRejects() {
        val s = fresh()
        s.tick(1e9); s.apply("arm")
        s.apply("estop")
        assertTrue(s.killLatched, "estop latches kill")
        assertFalse(s.apply("mission_start").first, "command rejected while latched")
        assertTrue(s.apply("arm").first, "re-arm recovers")
        assertFalse(s.killLatched)
    }

    @Test fun deadmanDoesNotAutoClear() {
        val s = fresh()
        s.tick(1e9); s.apply("arm")
        s.tick(1.0)
        assertTrue(s.killLatched, "deadman latches on heartbeat loss")
        s.tick(0.02)
        assertTrue(s.killLatched, "auto_clear=false: returning heartbeat does NOT re-energize")
    }

    @Test fun autoClearRecovers() {
        val s = SafetyState(heartbeatTimeoutSec = 0.5, autoClearKill = true)
        assertTrue(s.apply("arm").first)
        s.tick(1.0)
        assertTrue(s.killLatched, "deadman latches")
        s.tick(0.01)
        assertFalse(s.killLatched, "auto_clear=true: re-energizes when heartbeat returns while armed")
    }
}
