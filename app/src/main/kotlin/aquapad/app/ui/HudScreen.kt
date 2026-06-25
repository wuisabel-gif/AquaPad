package aquapad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import aquapad.protocol.TelemetryFrame

private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF16213A)
private val Green = Color(0xFF36D399)
private val Amber = Color(0xFFF5A524)
private val Red = Color(0xFFEF4444)
private val Dim = Color(0xFF8B98B8)

@Composable
fun HudScreen(
    frame: TelemetryFrame?,
    connected: Boolean,
    events: List<String>,
    onEstop: () -> Unit,
    onArm: () -> Unit,
    onMissionStart: () -> Unit,
    onMissionStop: () -> Unit,
    onMarker: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SafetyBanner(frame, connected)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("DEPTH", frame?.depth?.let { "%.2f m".format(it) } ?: "—", Modifier.weight(1f))
                Tile("BATTERY", frame?.batt?.let { "%d%%".format((it * 100).toInt()) } ?: "—", Modifier.weight(1f))
                Tile("LINK", frame?.hbAge?.let { "%.0f ms".format(it * 1000) } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("ROLL/PITCH/YAW", frame?.rpy?.joinToString(" ") { "%+.2f".format(it) } ?: "—", Modifier.weight(1f))
                Tile("DVL m/s", frame?.dvlVel?.joinToString(" ") { "%+.2f".format(it) } ?: "—", Modifier.weight(1f))
            }
            HealthRow(frame)
            EventLog(events)
        }

        Column(
            Modifier.width(220.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EstopButton(onEstop)
            OpButton("ARM / DISARM", Panel, onArm)
            OpButton("START MISSION", Panel, onMissionStart)
            OpButton("STOP MISSION", Panel, onMissionStop)
            OpButton("MARKER", Panel, onMarker)
        }
    }
}

@Composable
private fun SafetyBanner(frame: TelemetryFrame?, connected: Boolean) {
    val (label, color) = when {
        !connected -> "NO LINK" to Red
        frame == null -> "WAITING…" to Amber
        frame.killLatched -> "KILLED" to Red
        frame.armed -> "ARMED" to Green
        else -> "SAFE" to Amber
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Text(
            label,
            Modifier.fillMaxWidth().padding(14.dp),
            color = Color.Black,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun HealthRow(frame: TelemetryFrame?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        frame?.health?.forEach { (name, status) ->
            val c = when (status) { "ok" -> Green; "stale" -> Amber; else -> Red }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Text("  $name", color = Dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EventLog(events: List<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp)) {
            Text("LOG", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            events.forEach { Text(it, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun EstopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Red),
    ) {
        Text("E-STOP", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
    }
}

@Composable
private fun OpButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
