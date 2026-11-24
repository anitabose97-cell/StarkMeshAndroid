package com.starkmesh.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

data class OrbitSatNode(
    val id: String,
    val name: String,
    val orbitType: String,
    val lat: Double,
    val lon: Double,
    val altKm: Double,
    val velKmh: Double,
    val inclinationDeg: Double,
    val phaseOffsetDeg: Double,
    val color: Color
)

@Composable
fun SatelliteAccessLiveView(
    persona: String,
    onBackToHud: () -> Unit
) {
    val accent = if (persona == "friday") Color(0xFFFF5E8E) else Color(0xFF00E5FF)
    var selectedId by remember { mutableStateOf("ISS") }
    var tickSec by remember { mutableDoubleStateOf(0.0) }
    var issLive by remember { mutableStateOf(Triple(22.98, 88.37, 408.0)) }
    var issVel by remember { mutableDoubleStateOf(27600.0) }
    var issVis by remember { mutableStateOf("SYNCING") }

    val http = remember {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    LaunchedEffect(Unit) {
        while (true) {
            tickSec += 1.0
            if (tickSec.toInt() % 8 == 1) {
                withContext(Dispatchers.IO) {
                    try {
                        val req = Request.Builder().url("https://api.wheretheiss.at/v1/satellites/25544").build()
                        http.newCall(req).execute().use { r ->
                            if (r.isSuccessful) {
                                val j = JSONObject(r.body?.string() ?: "{}")
                                issLive = Triple(
                                    j.optDouble("latitude", issLive.first),
                                    j.optDouble("longitude", issLive.second),
                                    j.optDouble("altitude", 408.0)
                                )
                                issVel = j.optDouble("velocity", 27600.0)
                                issVis = j.optString("visibility", "DAYLIGHT").uppercase()
                            }
                        }
                    } catch (_: Exception) {
                        issVis = "PROPAGATED"
                    }
                }
            }
            delay(1000L)
        }
    }

    val satellites = remember(tickSec, issLive, issVel) {
        val t = tickSec
        val (iLat, iLon, iAlt) = issLive
        listOf(
            OrbitSatNode("ISS", "ISS (ZARYA)", "LEO Crewed", iLat, iLon, iAlt, issVel, 51.6, 0.0, Color(0xFFFFB300)),
            OrbitSatNode("EO-1", "CARTOSAT / EO-LEO", "LEO Earth Obs", 38.0 * sin(t * 0.035 + 1.1), ((iLon + 35.0 + t * 0.45 + 180.0) % 360.0) - 180.0, 520.0, 27100.0, 97.4, 28.0, Color(0xFF00E5FF)),
            OrbitSatNode("NAV-1", "NAVIC / GNSS-MEO", "MEO Navigation", 28.0 * sin(t * 0.02 + 2.4), ((iLon - 60.0 + t * 0.25 + 180.0) % 360.0) - 180.0, 20200.0, 13900.0, 55.0, -34.0, Color(0xFF69F0AE)),
            OrbitSatNode("MET-1", "INSAT / GEO-RELAY", "GEO Earth Watch", 12.0 * sin(t * 0.01), ((82.0 + sin(t * 0.015) * 25.0 + 180.0) % 360.0) - 180.0, 35786.0, 11070.0, 6.0, 12.0, Color(0xFFFF5E8E))
        )
    }

    val activeSat = satellites.find { it.id == selectedId } ?: satellites.first()
    val spin by rememberInfiniteTransition(label = "earthSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(28000, easing = LinearEasing)),
        label = "earthDeg"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050A12))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent, RoundedCornerShape(12.dp))
                .background(Color(0xFF0B1624), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SATELLITE ACCESS // LIVE EARTH", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("ISS Telemetry: $issVis • Tap satellite to focus", color = Color(0xFF9BB4CA), fontSize = 11.sp)
            }
            Text(
                "< HUD",
                color = Color(0xFFFFB300),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(8.dp))
                    .clickable { onBackToHud() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .border(1.dp, Color(0xFF1D354D), RoundedCornerShape(14.dp))
                .background(Color(0xFF040810), RoundedCornerShape(14.dp))
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = min(size.width, size.height) * 0.34f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF154360), Color(0xFF0B253A), Color(0xFF061320)),
                        center = Offset(cx - r * 0.25f, cy - r * 0.25f),
                        radius = r * 1.15f
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.35f),
                    radius = r * 1.03f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.5f)
                )

                val rotRad = Math.toRadians(spin.toDouble())
                for (m in -2..2) {
                    val lonOff = rotRad + (m * PI / 3.0)
                    val rx = (r * cos(lonOff)).toFloat()
                    if (abs(rx) > 2f) {
                        drawArc(
                            color = Color(0xFF2E86C1).copy(alpha = 0.28f),
                            startAngle = -90f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(cx - abs(rx), cy - r),
                            size = Size(abs(rx) * 2f, r * 2f),
                            style = Stroke(width = 1f)
                        )
                    }
                }
                for (latStep in listOf(-0.55f, 0f, 0.55f)) {
                    val py = cy + r * latStep
                    val chord = sqrt(max(0f, r * r - (r * latStep) * (r * latStep)))
                    drawLine(
                        color = Color(0xFF2E86C1).copy(alpha = 0.28f),
                        start = Offset(cx - chord, py),
                        end = Offset(cx + chord, py),
                        strokeWidth = 1f
                    )
                }

                val continents = listOf(
                    Triple(0.3, -0.22, 22f),
                    Triple(1.5, 0.15, 26f),
                    Triple(2.8, -0.35, 18f),
                    Triple(4.3, 0.25, 24f)
                )
                for ((baseLon, latN, sz) in continents) {
                    val ang = baseLon + rotRad * 0.65
                    val xRel = cos(ang) * cos(latN)
                    val zRel = sin(ang) * cos(latN)
                    val yRel = sin(latN)
                    if (zRel > -0.15) {
                        val px = cx + (xRel * r).toFloat()
                        val py = cy + (yRel * r).toFloat()
                        drawCircle(
                            color = Color(0xFF1E8449).copy(alpha = 0.45f),
                            radius = sz * (0.6f + 0.4f * zRel.toFloat().coerceIn(0f, 1f)),
                            center = Offset(px, py)
                        )
                    }
                }

                satellites.forEachIndexed { idx, sat ->
                    val orbitR = r * (1.16f + idx * 0.11f)
                    val tiltRad = Math.toRadians(sat.phaseOffsetDeg)
                    val path = Path()
                    val steps = 72
                    for (s in 0..steps) {
                        val a = (s * 2.0 * PI) / steps
                        val ox = cos(a) * orbitR
                        val oy = sin(a) * orbitR * 0.36f
                        val rx = ox * cos(tiltRad) - oy * sin(tiltRad)
                        val ry = ox * sin(tiltRad) + oy * cos(tiltRad)
                        val px = cx + rx.toFloat()
                        val py = cy + ry.toFloat()
                        if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    val isSel = sat.id == activeSat.id
                    drawPath(
                        path = path,
                        color = sat.color.copy(alpha = if (isSel) 0.75f else 0.28f),
                        style = Stroke(width = if (isSel) 2.2f else 1.2f)
                    )

                    val satAng = Math.toRadians(sat.lon + tickSec * (2.5 + idx * 1.3))
                    val sx0 = cos(satAng) * orbitR
                    val sy0 = sin(satAng) * orbitR * 0.36f
                    val sx = cx + (sx0 * cos(tiltRad) - sy0 * sin(tiltRad)).toFloat()
                    val sy = cy + (sx0 * sin(tiltRad) + sy0 * cos(tiltRad)).toFloat()

                    val gx = cx + (cos(satAng) * r * 0.85f).toFloat()
                    val gy = cy + (sin(satAng) * r * 0.32f).toFloat()
                    drawLine(
                        color = sat.color.copy(alpha = if (isSel) 0.55f else 0.18f),
                        start = Offset(sx, sy),
                        end = Offset(gx, gy),
                        strokeWidth = 1f
                    )
                    drawCircle(
                        color = sat.color,
                        radius = if (isSel) 7.5f else 5.2f,
                        center = Offset(sx, sy)
                    )
                    if (isSel) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.8f),
                            radius = 11f,
                            center = Offset(sx, sy),
                            style = Stroke(width = 1.5f)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, activeSat.color, RoundedCornerShape(12.dp))
                .background(Color(0xFF0B1624), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${activeSat.name} • ${activeSat.orbitType}",
                color = activeSat.color,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                String.format(Locale.US, "LAT: %.3f°   LON: %.3f°", activeSat.lat, activeSat.lon),
                color = Color(0xFFEAF6FF),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                String.format(Locale.US, "ALT: %.0f km   VEL: %.0f km/h   INC: %.1f°", activeSat.altKm, activeSat.velKmh, activeSat.inclinationDeg),
                color = Color(0xFF9BB4CA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        satellites.forEach { sat ->
            val selected = sat.id == activeSat.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (selected) sat.color else Color(0xFF1E344A), RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFF112236) else Color(0xFF0A131E), RoundedCornerShape(10.dp))
                    .clickable { selectedId = sat.id }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(sat.name, color = sat.color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${sat.orbitType} • Alt ${sat.altKm.toInt()} km", color = Color(0xFF9BB4CA), fontSize = 11.sp)
                }
                Text(
                    String.format(Locale.US, "%.1f°, %.1f°", sat.lat, sat.lon),
                    color = Color(0xFFD6E8F7),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
