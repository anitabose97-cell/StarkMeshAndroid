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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class NeuralVoiceEngine(private val context: Context) {
    companion object {
        const val JARVIS_DIR = "vits-piper-en_GB-alan-medium"
        const val JARVIS_ONNX = "en_GB-alan-medium.onnx"
        const val JARVIS_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2"
        const val FRIDAY_DIR = "vits-piper-en_GB-jenny_dioco-medium"
        const val FRIDAY_ONNX = "en_GB-jenny_dioco-medium.onnx"
        const val FRIDAY_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-jenny_dioco-medium.tar.bz2"
    }

    private val modelsRoot = File(context.filesDir, "neural_voices").apply { mkdirs() }
    private val http = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var loadedPersona: String? = null
    private var tts: OfflineTts? = null
    private var fallback: TextToSpeech? = null

    init {
        fallback = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) fallback?.language = Locale.UK }
    }

    fun areVoicesInstalled(): Boolean =
        File(modelsRoot, "$JARVIS_DIR/$JARVIS_ONNX").exists() &&
            File(modelsRoot, "$FRIDAY_DIR/$FRIDAY_ONNX").exists()

    suspend fun downloadAndInstallVoices(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Downloading J.A.R.V.I.S. voice...")
            extract(JARVIS_URL, modelsRoot)
            onProgress("Downloading F.R.I.D.A.Y. voice...")
            extract(FRIDAY_URL, modelsRoot)
            onProgress("Neural voices ready offline.")
            true
        } catch (e: Exception) {
            onProgress("Error: ${e.message}")
            false
        }
    }

    private fun extract(url: String, dest: File) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(r.body!!.byteStream()))).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val out = File(dest, e.name)
                    if (!out.canonicalPath.startsWith(dest.canonicalPath)) throw SecurityException("Path")
                    if (e.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                    e = tar.nextEntry
                }
            }
        }
    }

    private fun loadModel(persona: String): OfflineTts? {
        if (loadedPersona == persona && tts != null) return tts
        val dir = if (persona == "friday") FRIDAY_DIR else JARVIS_DIR
        val onnx = if (persona == "friday") FRIDAY_ONNX else JARVIS_ONNX
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
        loadedPersona = persona
        return tts
    }

    suspend fun speak(text: String, persona: String) = mutex.withLock {
        val clean = text.replace(Regex("[*_`#]"), "").trim()
        if (clean.isBlank()) return@withLock
        withContext(Dispatchers.IO) {
            val eng = loadModel(persona)
            if (eng == null) {
                fallback?.setPitch(if (persona == "friday") 1.15f else 0.88f)
                fallback?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fb")
                return@withContext
            }
            val gen = eng.generate(text = clean, sid = 0, speed = if (persona == "friday") 1.04f else 0.97f)
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
