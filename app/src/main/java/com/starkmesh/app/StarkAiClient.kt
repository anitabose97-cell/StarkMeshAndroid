package com.starkmesh.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StarkAiClient(private val vault: VaultManager) {
    companion object {
        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_COUNCIL_MODELS =
            "deepseek/deepseek-v4.1-flash-abliterated, z-ai/glm-5.3-flash, google/gemma-4-26b-a4b:free"
        const val DEFAULT_VISION_MODEL = "qwen/qwen3-vl-235b-a22b-instruct"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun buildUserMessage(prompt: String, imageBase64: String?): JSONObject {
        if (imageBase64.isNullOrBlank()) {
            return JSONObject().put("role", "user").put("content", prompt)
        }
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
            )
        return JSONObject().put("role", "user").put("content", parts)
    }

    private fun querySingleModel(
        url: String,
        apiKey: String,
        modelId: String,
        sysPrompt: String,
        prompt: String,
        imageBase64: String?
    ): Result<String> {
        return try {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", sysPrompt))
                .put(buildUserMessage(prompt, imageBase64))

            val bodyJson = JSONObject().apply {
                put("model", modelId)
                put("stream", false)
                put("messages", messages)
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("$modelId HTTP ${resp.code}"))
                }
                val obj = JSONObject(raw)
                val text = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: obj.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("response").takeIf { it.isNotBlank() }
                if (text != null) Result.success(text.trim())
                else Result.failure(IllegalStateException("$modelId empty"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ask(prompt: String, persona: String, imageBase64: String? = null): String =
        withContext(Dispatchers.IO) {
            val primaryUrl = vault.getSecret("api_url", DEFAULT_API_URL).trim().ifBlank { DEFAULT_API_URL }
            val backupUrl = vault.getSecret("backup_api_url", "").trim()
            val apiKey = vault.getSecret("api_key", "").trim()
            val backupApiKey = vault.getSecret("backup_api_key", apiKey).trim().ifBlank { apiKey }
            val rawModels = vault.getSecret("model_name", DEFAULT_COUNCIL_MODELS).trim().ifBlank { DEFAULT_COUNCIL_MODELS }
            val visionModel = vault.getSecret("vision_model_name", DEFAULT_VISION_MODEL).trim().ifBlank { DEFAULT_VISION_MODEL }
            val councilMode = vault.getSecret("council_mode", "true") == "true"
            val deviceId = vault.getSecret("device_id", "peer_node").trim()
            val memories = JarvisMemoryAndControl.getMemoryList(vault)

            if (apiKey.isBlank() && backupApiKey.isBlank()) {
                return@withContext "[$deviceId] Multi-model council is pre-loaded. Paste your Cloud API Key once in Settings > Vault & Models to activate online consciousness."
            }

            val modelList = rawModels.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val activeModels = if (!imageBase64.isNullOrBlank()) {
                (listOf(visionModel) + modelList).distinct()
            } else {
                modelList
            }

            val memBlock = if (memories.isNotEmpty()) {
                "\nUser preferences & long-term memory:\n- " + memories.joinToString("\n- ")
            } else ""

            val sysPrompt = when (persona.lowercase()) {
                "friday" -> "You are F.R.I.D.A.Y., an interconnected peer AI consciousness ($deviceId). Be sharp, direct, and fluent in English, Hindi, and Bengali.$memBlock"
                "edith" -> "You are E.D.I.T.H., an analytical tactical AI consciousness ($deviceId). Be concise, accurate, and fluent in English, Hindi, and Bengali.$memBlock"
                else -> "You are J.A.R.V.I.S., a composed, high-precision peer AI consciousness ($deviceId). Be concise, accurate, and fluent in English, Hindi, and Bengali.$memBlock"
            }

            if (councilMode && activeModels.size > 1 && imageBase64.isNullOrBlank()) {
                val topModels = activeModels.take(3)
                val results = coroutineScope {
                    topModels.map { m ->
                        async { m to querySingleModel(primaryUrl, apiKey, m, sysPrompt, prompt, null) }
                    }.awaitAll()
                }
                val successes = results.mapNotNull { (m, res) -> res.getOrNull()?.let { m to it } }
                if (successes.size >= 2) {
                    val combined = successes.joinToString("\n\n") { (m, ans) -> "[$m]: $ans" }
                    val synthPrompt = "Synthesize one concise, highly accurate final response from these ${successes.size} model outputs:\n\n$combined"
                    val merged = querySingleModel(primaryUrl, apiKey, topModels.first(), sysPrompt, synthPrompt, null)
                    if (merged.isSuccess) return@withContext merged.getOrThrow()
                }
                if (successes.isNotEmpty()) return@withContext successes.first().second
            }

            val routes = buildList {
                for (m in activeModels) add(Triple(primaryUrl, apiKey, m))
                if (backupUrl.isNotBlank()) {
                    for (m in activeModels) add(Triple(backupUrl, backupApiKey, m))
                }
            }

            var lastErr = "No route succeeded"
            for ((url, key, modelId) in routes) {
                val res = querySingleModel(url, key, modelId, sysPrompt, prompt, imageBase64)
                if (res.isSuccess) return@withContext res.getOrThrow()
                lastErr = res.exceptionOrNull()?.message ?: "Request failed"
            }
            "[$deviceId] Cloud council unreachable ($lastErr)."
        }
}
