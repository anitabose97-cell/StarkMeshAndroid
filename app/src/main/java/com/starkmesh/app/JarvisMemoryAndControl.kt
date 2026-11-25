package com.starkmesh.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

object JarvisMemoryAndControl {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun timeGreeting(userName: String = "Sir"): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 4..11 -> "Good morning, $userName. All systems and satellite links are online."
            in 12..16 -> "Good afternoon, $userName. Neural consciousness and device mesh are active."
            in 17..20 -> "Good evening, $userName. Ambient telemetry and voice core standing by."
            else -> "Good night, $userName. Background sentinel and call guard remain active."
        }
    }

    suspend fun getMemoryList(vault: VaultManager): MutableList<String> {
        val raw = vault.getSecret("jarvis_memories_json", "[]")
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return MutableList(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }.toMutableList()
    }

    suspend fun rememberFact(vault: VaultManager, fact: String) {
        val list = getMemoryList(vault)
        val clean = fact.trim()
        if (clean.isNotBlank() && !list.contains(clean)) {
            list.add(clean)
            if (list.size > 60) list.removeAt(0)
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            vault.putSecret("jarvis_memories_json", arr.toString())
        }
    }

    suspend fun getSavedPasswords(vault: VaultManager): MutableMap<String, String> {
        val raw = vault.getSecret("jarvis_password_vault_json", "{}")
        val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { k -> map[k] = obj.optString(k) }
        return map
    }

    suspend fun savePasswordEntry(vault: VaultManager, siteOrLabel: String, secretVal: String) {
        val map = getSavedPasswords(vault)
        map[siteOrLabel.trim().lowercase()] = secretVal.trim()
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        vault.putSecret("jarvis_password_vault_json", obj.toString())
    }

    suspend fun fetchLiveWeather(lat: Double = 22.975, lon: Double = 88.434): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "Weather telemetry temporarily unavailable."
                val cur = JSONObject(r.body?.string() ?: "{}").optJSONObject("current")
                    ?: return@withContext "Weather data unavailable."
                val temp = cur.optDouble("temperature_2m", 0.0)
                val hum = cur.optInt("relative_humidity_2m", 0)
                val wind = cur.optDouble("wind_speed_10m", 0.0)
                "Current weather: ${temp}°C, humidity ${hum}%, wind ${wind} km/h."
            }
        } catch (e: Exception) {
            "Offline weather cache: ${e.message}"
        }
    }

    fun scanPrivateFiles(query: String? = null, maxItems: Int = 15): List<File> {
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStorageDirectory()
        )
        val found = mutableListOf<File>()
        val q = query?.trim()?.lowercase().orEmpty()
        for (root in roots) {
            if (root == null || !root.exists()) continue
            root.walkTopDown().maxDepth(3).forEach { f ->
                if (found.size >= maxItems) return@forEach
                if (f.isFile && !f.name.startsWith(".")) {
                    if (q.isBlank() || f.name.lowercase().contains(q)) {
                        if (found.none { it.absolutePath == f.absolutePath }) found.add(f)
                    }
                }
            }
        }
        return found
    }

    fun readFileSnippet(file: File, maxChars: Int = 2500): String {
        return try {
            val ext = file.extension.lowercase()
            if (ext in listOf("txt", "md", "json", "csv", "log", "xml", "kt", "py", "html")) {
                file.readText(Charsets.UTF_8).take(maxChars)
            } else {
                "Binary/Document file: ${file.name} (${file.length() / 1024} KB) at ${file.absolutePath}"
            }
        } catch (e: Exception) {
            "Unable to read ${file.name}: ${e.message}"
        }
    }

    fun executeDeviceControl(context: Context, command: String): String? {
        val low = command.lowercase()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            low.contains("volume up") || low.contains("increase volume") -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Media volume increased."
            }
            low.contains("volume down") || low.contains("decrease volume") -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Media volume decreased."
            }
            low.contains("mute") -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Device audio muted."
            }
            low.contains("wifi settings") || low.contains("wi-fi") -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opening Wi-Fi controls."
            }
            low.contains("bluetooth") -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opening Bluetooth controls."
            }
            low.contains("flashlight") || low.contains("torch") -> {
                try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val id = cm.cameraIdList.firstOrNull()
                    if (id != null) {
                        val turnOn = !low.contains("off")
                        cm.setTorchMode(id, turnOn)
                        if (turnOn) "Flashlight activated." else "Flashlight turned off."
                    } else "No flash hardware found."
                } catch (e: Exception) {
                    "Torch control unavailable: ${e.message}"
                }
            }
            low.startsWith("open ") -> {
                val appQuery = low.removePrefix("open ").trim()
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(0)
                val match = apps.firstOrNull {
                    pm.getApplicationLabel(it).toString().lowercase().contains(appQuery)
                }
                if (match != null) {
                    val launch = pm.getLaunchIntentForPackage(match.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        "Launching ${pm.getApplicationLabel(match)}."
                    } else "App ${match.packageName} has no launch screen."
                } else {
                    "Could not find installed app matching '$appQuery'."
                }
            }
            else -> null
        }
    }
}
