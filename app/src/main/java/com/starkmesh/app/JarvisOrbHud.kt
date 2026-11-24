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
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altKm: Double = 408.0,
    val velKmh: Double = 27600.0,
    val visibility: String = "SYNCING",
    val online: Boolean = false
)

@Composable
fun rememberSatelliteTelemetry(): SatelliteTelemetry {
    var sat by remember { mutableStateOf(SatelliteTelemetry()) }
    val http = remember { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build() }

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
                            lat = j.optDouble("latitude", 0.0),
                            lon = j.optDouble("longitude", 0.0),
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
                        visibility = "OFFLINE EPHEM.",
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
    modifier: Modifier = Modifier
) {
    val primaryColor = if (persona == "friday") Color(0xFFFF5E8E) else Color(0xFFFFB300)
    val secondaryColor = if (persona == "friday") Color(0xFF00E5FF) else Color(0xFFFF6F00)

    val inf = rememberInfiniteTransition(label = "orb")
    val spinDeg by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 4500 else 11000, easing = LinearEasing)
        ),
        label = "spin"
    )
    val pulse by inf.animateFloat(
        initialValue = 0.92f,
        targetValue = if (isActive) 1.10f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 550 else 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val spherePoints = remember {
        val count = 130
        val golden = PI * (3.0 - sqrt(5.0))
        List(count) { i ->
            val y = 1.0 - (i / (count - 1).toDouble()) * 2.0
            val r = sqrt(max(0.0, 1.0 - y * y))
            val theta = golden * i
            Triple(cos(theta) * r, y, sin(theta) * r)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, primaryColor.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .background(Color(0xFF09121E), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(modifier = Modifier.size(116.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = (size.minDimension / 2.35f) * pulse
            val radY = Math.toRadians(spinDeg.toDouble())
            val radX = Math.toRadians((spinDeg * 0.45).toDouble())

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseR * 1.25f
                ),
                radius = baseR * 1.25f,
                center = Offset(cx, cy)
            )

            drawCircle(primaryColor.copy(alpha = 0.35f), radius = baseR * 1.08f, center = Offset(cx, cy), style = Stroke(1.5f))
            drawCircle(secondaryColor.copy(alpha = 0.5f), radius = baseR * 0.72f, center = Offset(cx, cy), style = Stroke(1.2f))

            val satAngle = Math.toRadians(sat.lon + spinDeg)
            val sx = cx + cos(satAngle).toFloat() * (baseR * 1.08f)
            val sy = cy + sin(satAngle).toFloat() * (baseR * 1.08f)
            drawCircle(Color(0xFF00E5FF), radius = 4.5f, center = Offset(sx, sy))

            val projected = ArrayList<Triple<Float, Float, Float>>(spherePoints.size)
            for ((px, py, pz) in spherePoints) {
                val x1 = px * cos(radY) - pz * sin(radY)
                val z1 = px * sin(radY) + pz * cos(radY)
                val y2 = py * cos(radX) - z1 * sin(radX)
                val z2 = py * sin(radX) + z1 * cos(radX)
                val depth = ((z2 + 1.4) / 2.4).toFloat().coerceIn(0.15f, 1.0f)
                projected.add(Triple(cx + (x1 * baseR).toFloat(), cy + (y2 * baseR).toFloat(), depth))
            }

            for (i in 0 until projected.size step 2) {
                val (x1, y1, d1) = projected[i]
                if (d1 > 0.58f) {
                    val (x2, y2, d2) = projected[(i + 7) % projected.size]
                    if (d2 > 0.58f) {
                        drawLine(
                            color = primaryColor.copy(alpha = 0.24f * d1),
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 1f
                        )
                    }
                }
            }

            for ((x, y, depth) in projected) {
                drawCircle(
                    color = if (depth > 0.75f) Color.White.copy(alpha = 0.85f) else primaryColor.copy(alpha = depth),
                    radius = 1.4f + depth * 2.1f,
                    center = Offset(x, y)
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (persona == "friday") "F.R.I.D.A.Y. // NEURAL CORE" else "J.A.R.V.I.S. // NEURAL MATRIX",
                color = primaryColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "SAT: ${sat.name} [${sat.visibility}]",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = String.format(Locale.US, "LAT %.2f°  LON %.2f°", sat.lat, sat.lon),
                color = Color(0xFFD6E8F7),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = String.format(Locale.US, "ALT %.0f km • %.0f km/h", sat.altKm, sat.velKmh),
                color = Color(0xFF8FB3D9),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
