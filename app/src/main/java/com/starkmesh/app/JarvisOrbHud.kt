package com.starkmesh.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class SatelliteTelemetry(
    val name: String = "ISS (ZARYA)",
    val lat: Double = 22.98,
    val lon: Double = 88.37,
    val altKm: Double = 408.0,
    val velKmh: Double = 27600.0,
    val visibility: String = "SYNCING",
    val online: Boolean = false
)

@Composable
fun rememberSatelliteTelemetry(): SatelliteTelemetry {
    var sat by remember { mutableStateOf(SatelliteTelemetry()) }
    val http = remember {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    LaunchedEffect(Unit) {
        while (true) {
            sat = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url("https://api.wheretheiss.at/v1/satellites/25544").build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@withContext sat.copy(visibility = "ORBIT EST.", online = false)
                        val j = JSONObject(r.body?.string() ?: "{}")
                        SatelliteTelemetry(
                            name = "ISS-25544",
                            lat = j.optDouble("latitude", 22.98),
                            lon = j.optDouble("longitude", 88.37),
                            altKm = j.optDouble("altitude", 408.0),
                            velKmh = j.optDouble("velocity", 27600.0),
                            visibility = j.optString("visibility", "DAYLIGHT").uppercase(),
                            online = true
                        )
                    }
                } catch (_: Exception) {
                    val t = System.currentTimeMillis() / 1000.0
                    sat.copy(
                        lat = 51.6 * sin(t / 880.0),
                        lon = ((t * 0.065) % 360.0) - 180.0,
                        visibility = "EPHEMERIS",
                        online = false
                    )
                }
            }
            delay(10_000L)
        }
    }
    return sat
}

@Composable
fun JarvisHologramOrb(
    persona: String,
    isActive: Boolean,
    sat: SatelliteTelemetry,
    weatherSummary: String,
    modifier: Modifier = Modifier
) {
    val primaryColor = when (persona.lowercase()) {
        "friday" -> Color(0xFFFF4081)
        "edith" -> Color(0xFF00E676)
        else -> Color(0xFFFFB300)
    }
    val secondaryColor = when (persona.lowercase()) {
        "friday" -> Color(0xFF00E5FF)
        "edith" -> Color(0xFF69F0AE)
        else -> Color(0xFF00E5FF)
    }

    val inf = rememberInfiniteTransition(label = "infiniteOrb")
    val spinDeg by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (isActive) 3800 else 9500, easing = LinearEasing)),
        label = "spin"
    )
    val counterSpin by inf.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(if (isActive) 5200 else 14000, easing = LinearEasing)),
        label = "counterSpin"
    )
    val pulse by inf.animateFloat(
        initialValue = 0.90f,
        targetValue = if (isActive) 1.12f else 1.03f,
        animationSpec = infiniteRepeatable(
            tween(if (isActive) 480 else 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // 320 multi-shell Fibonacci lattice particles for ultra-dense Age of Ultron core
    val particleShells = remember {
        val count = 320
        val golden = PI * (3.0 - sqrt(5.0))
        List(count) { i ->
            val shell = when {
                i < 160 -> 1.0
                i < 260 -> 0.68
                else -> 1.24
            }
            val idx = i % 160
            val y = 1.0 - (idx / 159.0) * 2.0
            val r = sqrt(max(0.0, 1.0 - y * y)) * shell
            val theta = golden * i
            Triple(cos(theta) * r, y * shell, sin(theta) * r)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, primaryColor.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(primaryColor.copy(alpha = 0.14f), Color(0xFF08111E), secondaryColor.copy(alpha = 0.10f))
                ),
                RoundedCornerShape(16.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(modifier = Modifier.size(134.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = (size.minDimension / 2.55f) * pulse
            val radY = Math.toRadians(spinDeg.toDouble())
            val radX = Math.toRadians((spinDeg * 0.42).toDouble())
            val radCounter = Math.toRadians(counterSpin.toDouble())

            // Ambient volumetric glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.45f), secondaryColor.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseR * 1.48f
                ),
                radius = baseR * 1.48f,
                center = Offset(cx, cy)
            )

            // Gyroscopic HUD rings
            drawCircle(primaryColor.copy(alpha = 0.45f), radius = baseR * 1.26f, center = Offset(cx, cy), style = Stroke(1.4f))
            drawCircle(secondaryColor.copy(alpha = 0.45f), radius = baseR * 0.92f, center = Offset(cx, cy), style = Stroke(1.1f))
            drawCircle(Color.White.copy(alpha = 0.35f), radius = baseR * 0.48f, center = Offset(cx, cy), style = Stroke(1.0f))

            // Live ISS satellite blip orbiting outer ring
            val satAngle = Math.toRadians(sat.lon + spinDeg)
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = 4.8f,
                center = Offset(cx + cos(satAngle).toFloat() * (baseR * 1.26f), cy + sin(satAngle).toFloat() * (baseR * 1.26f))
            )

            val projected = ArrayList<Triple<Float, Float, Float>>(particleShells.size)
            for (i in particleShells.indices) {
                val (px, py, pz) = particleShells[i]
                val rot = if (i >= 160 && i < 260) radCounter else radY
                val x1 = px * cos(rot) - pz * sin(rot)
                val z1 = px * sin(rot) + pz * cos(rot)
                val y2 = py * cos(radX) - z1 * sin(radX)
                val z2 = py * sin(radX) + z1 * cos(radX)
                val depth = ((z2 + 1.5) / 2.8).toFloat().coerceIn(0.12f, 1.0f)
                projected.add(Triple(cx + (x1 * baseR).toFloat(), cy + (y2 * baseR).toFloat(), depth))
            }

            // Neural synapse filaments on foreground hemisphere
            for (i in 0 until 160 step 2) {
                val (x1, y1, d1) = projected[i]
                if (d1 > 0.60f) {
                    val (x2, y2, d2) = projected[(i + 9) % 160]
                    if (d2 > 0.60f) {
                        drawLine(
                            color = primaryColor.copy(alpha = 0.28f * d1),
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 1.0f
                        )
                    }
                }
            }

            // Render all 320 glowing particles
            for (i in projected.indices) {
                val (x, y, depth) = projected[i]
                val pCol = when {
                    i >= 260 -> secondaryColor.copy(alpha = depth * 0.85f)
                    depth > 0.78f -> Color.White.copy(alpha = 0.92f)
                    else -> primaryColor.copy(alpha = depth)
                }
                drawCircle(
                    color = pCol,
                    radius = (1.1f + depth * 1.85f),
                    center = Offset(x, y)
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = when (persona.lowercase()) {
                    "friday" -> "F.R.I.D.A.Y. // CONSCIOUSNESS"
                    "edith" -> "E.D.I.T.H. // TACTICAL MATRIX"
                    else -> "J.A.R.V.I.S. // NEURAL CORE"
                },
                color = primaryColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = String.format(Locale.US, "SAT: %s (%.1f°, %.1f°)", sat.name, sat.lat, sat.lon),
                color = Color(0xFF00E5FF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = weatherSummary,
                color = Color(0xFFEAF6FF),
                fontSize = 10.sp
            )
            Text(
                text = if (JarvisScreenService.isRunning) "SCREEN LINK: LIVE" else "MIC & MESH: ARMED",
                color = Color(0xFF69F0AE),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
