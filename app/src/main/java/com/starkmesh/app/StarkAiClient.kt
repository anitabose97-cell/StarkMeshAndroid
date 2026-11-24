package com.starkmesh.app

import kotlinx.coroutines.Dispatchers; import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType; import okhttp3.OkHttpClient; import okhttp3.Request; import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray; import org.json.JSONObject; import java.util.concurrent.TimeUnit

class StarkAiClient(private val vault: VaultManager) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val jt = "application/json; charset=utf-8".toMediaType()

    private suspend fun sysPrompt(persona: String): String {
        val id = vault.getSecret("device_id", "vivo_v15_pro"); val known = vault.getSecret("known_devices", "vivo_v15_pro, oppo_a16e, raspberry_pi_5")
        val busy = vault.getSecret("busy_mode", "false"); val rsn = vault.getSecret("busy_reason", "busy")
        val role = if (persona == "friday") "You are F.R.I.D.A.Y., a warm, caring, playfully flirty AI companion." else "You are J.A.R.V.I.S., a sharp, composed, wry British AI assistant."
        return "$role Device:$id Mesh:[$known] Busy:$busy($rsn). Each device stores its own PIN/password locally in its encrypted vault; call control_device(target, action) directly without asking for passwords. Wellness/camera checks are general wellness observations, not medical diagnoses."
    }

    private fun tools(): JSONArray = JSONArray()
        .put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "control_device").put("description", "Lock, unlock, or check status of a device.").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("target", JSONObject().put("type", "string")).put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("lock", "unlock", "status"))))).put("required", JSONArray(listOf("target", "action"))))))
        .put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "set_busy_mode").put("description", "Toggle Busy Mode.").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("enabled", JSONObject().put("type", "boolean")).put("reason", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("enabled"))))))

    suspend fun publishRelay(target: String, action: String): String = withContext(Dispatchers.IO) {
        val topic = vault.getSecret("relay_topic"); val token = vault.getSecret("relay_token"); val myId = vault.getSecret("device_id", "vivo_v15_pro")
        if (target == myId) return@withContext when (action) {
            "lock" -> { StarkAccessibilityService.instance?.lockScreenNow(); "Locked $myId." }
            "unlock" -> { StarkAccessibilityService.instance?.wakeAndUnlockWithPin(vault.getSecret("device_pin")); "Unlocking $myId." }
            else -> "$myId online."
        }
        if (topic.isBlank()) return@withContext "No relay topic."
        val body = JSONObject().put("target", target).put("action", action).put("token", token).put("from", myId).put("ts", System.currentTimeMillis())
        http.newCall(Request.Builder().url("https://ntfy.sh/$topic").post(body.toString().toRequestBody(jt)).build()).execute().use {
            if (it.isSuccessful) "Sent $action to $target." else "Relay HTTP ${it.code}"
        }
    }

    suspend fun ask(prompt: String, persona: String, imgB64: String? = null): String = withContext(Dispatchers.IO) {
        val url = vault.getSecret("api_url"); val key = vault.getSecret("api_key"); val model = vault.getSecret("model_name")
        if (url.isBlank() || key.isBlank() || model.isBlank()) return@withContext "Set API URL, Key, and Model in Vault."
        val uMsg = if (imgB64 != null) JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)).put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imgB64"))))
        else JSONObject().put("role", "user").put("content", prompt)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sysPrompt(persona))).put(uMsg)
        http.newCall(Request.Builder().url(url).addHeader("Authorization", "Bearer $key").post(JSONObject().put("model", model).put("messages", msgs).put("tools", tools()).toString().toRequestBody(jt)).build()).execute().use { r ->
            val raw = r.body?.string() ?: "{}"
            if (!r.isSuccessful) return@withContext "API HTTP ${r.code}"
            val m = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return@withContext "Bad response."
            val calls = m.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                msgs.put(m)
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i); val fn = c.getJSONObject("function").getString("name"); val args = JSONObject(c.getJSONObject("function").getString("arguments"))
                    val out = when (fn) {
                        "control_device" -> publishRelay(args.getString("target"), args.getString("action"))
                        "set_busy_mode" -> { val en = args.getBoolean("enabled"); val rsn = args.optString("reason", "busy"); vault.putSecret("busy_mode", en.toString()); vault.putSecret("busy_reason", rsn); "Busy=$en" }
                        else -> "Unknown tool"
                    }
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out))
                }
                http.newCall(Request.Builder().url(url).addHeader("Authorization", "Bearer $key").post(JSONObject().put("model", model).put("messages", msgs).toString().toRequestBody(jt)).build()).execute().use { r2 ->
                    return@withContext JSONObject(r2.body?.string() ?: "{}").optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "Done.") ?: "Done."
                }
            }
            return@withContext m.optString("content", "No reply.")
        }
    }
}
