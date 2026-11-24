package com.starkmesh.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class NeuralVoiceEngine(private val context: Context) {
    companion object {
        const val JARVIS_DIR = "vits-piper-en_GB-alan-medium"
        const val JARVIS_ONNX = "en_GB-alan-medium.onnx"
        const val FRIDAY_DIR = "vits-piper-en_GB-jenny_dioco-medium"
        const val FRIDAY_ONNX = "en_GB-jenny_dioco-medium.onnx"
    }

    private val modelsRoot = File(context.filesDir, "neural_voices").apply { mkdirs() }
    private val mutex = Mutex()
    private var loadedPersona: String? = null
    private var tts: OfflineTts? = null
    private var fallback: TextToSpeech? = null
    @Volatile private var fallbackReady = false

    init {
        fallback = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                fallbackReady = true
                fallback?.language = Locale("en", "IN")
            }
        }
    }

    fun areVoicesInstalled(): Boolean =
        File(modelsRoot, "$JARVIS_DIR/$JARVIS_ONNX").exists() &&
            File(modelsRoot, "$FRIDAY_DIR/$FRIDAY_ONNX").exists()

    suspend fun ensureBundledVoicesReady(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (areVoicesInstalled()) {
            onProgress("Bundled Neural Voices Ready")
            return@withContext true
        }
        try {
            onProgress("Checking bundled voice assets...")
            copyAssetTree("neural_voices/$JARVIS_DIR", File(modelsRoot, JARVIS_DIR))
            copyAssetTree("neural_voices/$FRIDAY_DIR", File(modelsRoot, FRIDAY_DIR))
            onProgress(if (areVoicesInstalled()) "Bundled Neural Voices Ready" else "Using Android TTS Engine")
            true
        } catch (e: Exception) {
            onProgress("Using Android TTS: ${e.message}")
            false
        }
    }

    private fun copyAssetTree(assetPath: String, destDir: File) {
        val list = context.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            if (!destDir.exists()) {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(destDir).use { output -> input.copyTo(output) }
                }
            }
        } else {
            destDir.mkdirs()
            for (child in list) {
                copyAssetTree("$assetPath/$child", File(destDir, child))
            }
        }
    }

    private fun detectLocale(text: String): Locale = when {
        text.any { it.code in 0x0980..0x09FF } -> Locale("bn", "IN")
        text.any { it.code in 0x0900..0x097F } -> Locale("hi", "IN")
        else -> Locale("en", "IN")
    }

    private fun personaPitch(persona: String): Float = when (persona) {
        "friday" -> 1.18f
        "edith" -> 1.05f
        else -> 0.85f
    }

    private fun personaSpeed(persona: String): Float = when (persona) {
        "friday" -> 1.06f
        "edith" -> 1.02f
        else -> 0.96f
    }

    private fun loadModel(persona: String): OfflineTts? {
        val basePersona = if (persona == "friday") "friday" else "jarvis"
        if (loadedPersona == basePersona && tts != null) return tts
        val dir = if (basePersona == "friday") FRIDAY_DIR else JARVIS_DIR
        val onnx = if (basePersona == "friday") FRIDAY_ONNX else JARVIS_ONNX
        val base = File(modelsRoot, dir)
        val m = File(base, onnx)
        val t = File(base, "tokens.txt")
        val d = File(base, "espeak-ng-data")
        if (!m.exists() || !t.exists() || !d.exists()) return null

        tts?.release()
        tts = null
        val vitsCfg = OfflineTtsVitsModelConfig(
            model = m.absolutePath,
            lexicon = "",
            tokens = t.absolutePath,
            dataDir = d.absolutePath
        )
        val modelCfg = OfflineTtsModelConfig(
            vits = vitsCfg,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelCfg))
        loadedPersona = basePersona
        return tts
    }

    suspend fun speak(text: String, persona: String) = mutex.withLock {
        val clean = text.replace(Regex("[*_`#]"), "").trim()
        if (clean.isBlank()) return@withLock
        withContext(Dispatchers.IO) {
            if (!areVoicesInstalled()) ensureBundledVoicesReady {}
            val loc = detectLocale(clean)
            val isIndic = loc.language == "hi" || loc.language == "bn"
            val eng = if (!isIndic) loadModel(persona) else null
            if (eng == null) {
                if (fallbackReady) {
                    fallback?.language = loc
                    fallback?.setPitch(personaPitch(persona))
                    fallback?.setSpeechRate(personaSpeed(persona))
                    fallback?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fb")
                }
                return@withContext
            }
            val gen = eng.generate(text = clean, sid = 0, speed = personaSpeed(persona))
            play(gen.samples, gen.sampleRate)
        }
    }

    private fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(pcm.size * 2)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        tr.write(pcm, 0, pcm.size)
        tr.play()
        Thread.sleep((samples.size * 1000L) / rate.coerceAtLeast(1) + 120L)
        tr.stop()
        tr.release()
    }

    fun release() {
        tts?.release()
        tts = null
        fallback?.shutdown()
    }
}
