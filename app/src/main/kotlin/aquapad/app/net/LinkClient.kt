package aquapad.app.net

import aquapad.protocol.Ack
import aquapad.protocol.Command
import aquapad.protocol.Heartbeat
import aquapad.protocol.Profile
import aquapad.protocol.TelemetryFrame
import aquapad.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone side of the AquaPad link: UDP heartbeat out (drives the robot-side deadman), UDP
 * telemetry in -> [telemetry], and acked WebSocket commands. Reuses the shared [Wire] codec
 * and [Profile] so app/sim/bridge can't drift on the wire.
 */
class LinkClient(
    private val profile: Profile,
    private val host: String,
    private val scope: CoroutineScope,
) {
    private val net = profile.network

    private val _telemetry = MutableStateFlow<TelemetryFrame?>(null)
    val telemetry: StateFlow<TelemetryFrame?> = _telemetry

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val cmdSeq = AtomicLong(1000)
    private var webSocket: WebSocket? = null
    private val http = OkHttpClient()
    private var ackListener: ((Ack) -> Unit)? = null

    fun start() {
        scope.launch(Dispatchers.IO) { heartbeatLoop() }
        scope.launch(Dispatchers.IO) { telemetryLoop() }
        connectCommands()
    }

    private suspend fun heartbeatLoop() {
        val sock = DatagramSocket()
        val addr = InetAddress.getByName(host)
        val periodMs = (1000.0 / net.heartbeatExpectedHz).toLong().coerceAtLeast(1)
        var seq = 0L
        while (scope.isActive) {
            val bytes = Wire.encode(Heartbeat(seq = seq++, t = now())).toByteArray()
            runCatching { sock.send(DatagramPacket(bytes, bytes.size, addr, net.udpListenPort)) }
            delay(periodMs)
        }
    }

    private suspend fun telemetryLoop() {
        val sock = DatagramSocket(net.udpTelemetryPort)
        val buf = ByteArray(8192)
        while (scope.isActive) {
            val pkt = DatagramPacket(buf, buf.size)
            runCatching {
                sock.receive(pkt)
                Wire.decodeFrame(String(pkt.data, 0, pkt.length))
            }.getOrNull()?.let { _telemetry.value = it }
        }
    }

    private fun connectCommands() {
        val req = Request.Builder().url("ws://$host:${net.wsPort}/").build()
        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { _connected.value = true }
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching { Wire.decodeAck(text) }.getOrNull()?.let { ackListener?.invoke(it) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                scope.launch(Dispatchers.IO) { delay(1000); connectCommands() }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { _connected.value = false }
        })
    }

    fun onAckReceived(listener: (Ack) -> Unit) { ackListener = listener }

    fun send(type: String, args: Map<String, String>? = null): Long {
        val seq = cmdSeq.incrementAndGet()
        webSocket?.send(Wire.encode(Command(type = type, seq = seq, t = now(), args = args)))
        return seq
    }

    fun estop() = send("estop")
    fun arm() = send("arm")
    fun disarm() = send("disarm")
    fun missionStart(plan: String) = send("mission_start", mapOf("plan" to plan))
    fun missionStop() = send("mission_stop")
    fun marker(label: String) = send("marker", mapOf("label" to label))

    fun stop() {
        webSocket?.close(1000, "client stop")
        webSocket = null
    }

    private fun now() = System.currentTimeMillis() / 1000.0

    companion object {
        fun loadProfile(yaml: String): Profile = Profile.parse(yaml)
    }
}
