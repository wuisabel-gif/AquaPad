package aquapad.sim

import aquapad.protocol.Ack
import aquapad.protocol.Command
import aquapad.protocol.Profile
import aquapad.protocol.SafetyState
import aquapad.protocol.TelemetryFrame
import aquapad.protocol.Wire
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Robot-agnostic simulator: stands in for (robot + aquapad_bridge) so the phone/app loop can
 * run with no robot and no ROS. Driven by a profile, it fakes the telemetry fields that
 * profile declares and runs the real safety semantics via the shared [SafetyState].
 *
 *   ./gradlew :sim:run --args="profiles/barracuda.yaml"
 */
class FakeRobot(private val profile: Profile) {
    private val safety = SafetyState(
        heartbeatTimeoutSec = profile.safety.heartbeatTimeoutSec,
        autoClearKill = profile.safety.autoClearKill,
    )
    private val lock = Any()
    private val lastHbNanos = AtomicLong(0L)
    private val phoneAddr = AtomicReference<InetAddress?>(null)
    private val txSeq = AtomicLong(0L)
    private val t0 = System.nanoTime()
    private var missionActive = false
    private val fields = profile.telemetry.keys.toList()

    fun start() {
        val net = profile.network
        val udp = DatagramSocket(net.udpListenPort)
        println("fake_robot '${profile.name}' up:")
        println("  UDP heartbeat/control  :${net.udpListenPort}")
        println("  UDP telemetry out      ->:${net.udpTelemetryPort} (to phone once heard)")
        println("  WS  commands           :${net.wsPort}")
        println("  deadman timeout        ${profile.safety.heartbeatTimeoutSec}s  autoClear=${profile.safety.autoClearKill}")

        thread(isDaemon = true, name = "udp-rx") { udpReceiveLoop(udp) }
        thread(isDaemon = true, name = "telemetry") { telemetryLoop(udp) }
        startCommandServer()
    }

    private fun udpReceiveLoop(udp: DatagramSocket) {
        val buf = ByteArray(2048)
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            udp.receive(pkt)
            val text = String(pkt.data, 0, pkt.length)
            runCatching { Wire.decodeHeartbeat(text) }.getOrNull()?.let {
                lastHbNanos.set(System.nanoTime())
                phoneAddr.set(pkt.address)
            }
        }
    }

    private fun telemetryLoop(udp: DatagramSocket) {
        val net = profile.network
        val periodMs = (1000.0 / net.telemetryRateHz).toLong().coerceAtLeast(1)
        while (true) {
            val hbAge = hbAgeSec()
            synchronized(lock) { safety.tick(hbAge) }
            val addr = phoneAddr.get()
            if (addr != null) {
                val frame = buildFrame(hbAge)
                val bytes = Wire.encode(frame).toByteArray()
                udp.send(DatagramPacket(bytes, bytes.size, addr, net.udpTelemetryPort))
            }
            Thread.sleep(periodMs)
        }
    }

    private fun hbAgeSec(): Double {
        val last = lastHbNanos.get()
        return if (last == 0L) 1e9 else (System.nanoTime() - last) / 1e9
    }

    private fun buildFrame(hbAge: Double): TelemetryFrame {
        val healthVal = if (hbAge > profile.safety.heartbeatTimeoutSec) "stale" else "ok"
        return synchronized(lock) {
            TelemetryFrame(
                t = System.currentTimeMillis() / 1000.0,
                seq = txSeq.incrementAndGet(),
                armed = safety.armed,
                killLatched = safety.killLatched,
                hbAge = round3(hbAge),
                missionActive = missionActive,
                depth = if ("depth" in fields) sampleDepth() else null,
                rpy = if ("rpy" in fields) sampleRpy() else null,
                dvlVel = if ("dvl_vel" in fields) sampleDvl() else null,
                batt = if ("batt" in fields) sampleBatt() else null,
                health = fields.associateWith { healthVal },
            )
        }
    }

    private fun secs() = (System.nanoTime() - t0) / 1e9
    private fun sampleDepth() = round3(2.0 + 0.4 * sin(secs() / 5))
    private fun sampleRpy(): List<Double> {
        val t = secs()
        return listOf(round3(0.05 * sin(t / 3)), round3(0.05 * cos(t / 4)), round3((t / 6) % (2 * Math.PI) - Math.PI))
    }
    private fun sampleDvl(): List<Double> {
        val v = if (missionActive) 0.3 else 0.0
        return listOf(round3(v * cos(secs() / 6)), 0.0, round3(0.02 * sin(secs())))
    }
    private fun sampleBatt() = round3(maxOf(0.0, 0.95 - secs() / 3600))

    private fun startCommandServer() {
        embeddedServer(Netty, port = profile.network.wsPort) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    println("[ws] command client connected")
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val cmd = runCatching { Wire.decodeCommand(frame.readText()) }.getOrNull()
                        if (cmd == null) {
                            send(Frame.Text(Wire.encode(Ack(ack = null, ok = false, detail = "bad json"))))
                            continue
                        }
                        val (ok, detail) = applyCommand(cmd)
                        println("[cmd] ${cmd.type} -> ok=$ok ($detail)")
                        send(Frame.Text(Wire.encode(Ack(ack = cmd.seq, ok = ok, detail = detail))))
                    }
                }
            }
        }.start(wait = true)
    }

    private fun applyCommand(cmd: Command): Pair<Boolean, String> = synchronized(lock) {
        when (cmd.type) {
            "mission_start" -> {
                if (safety.killLatched) return@synchronized false to "rejected: kill latched, send 'arm'"
                missionActive = true
                true to "mission '${cmd.args?.get("plan") ?: "default"}' started"
            }
            "mission_stop" -> {
                if (safety.killLatched) return@synchronized false to "rejected: kill latched, send 'arm'"
                missionActive = false
                true to "mission stopped"
            }
            "marker" -> {
                println("[MARKER] ${cmd.args ?: emptyMap<String, String>()}")
                true to "marker logged"
            }
            else -> safety.apply(cmd.type)
        }
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: fake_robot <profile.yaml>" }
    val profile = Profile.parse(java.io.File(args[0]).readText())
    FakeRobot(profile).start()
}
