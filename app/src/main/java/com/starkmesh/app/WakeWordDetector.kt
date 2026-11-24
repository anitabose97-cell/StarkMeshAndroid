package com.starkmesh.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class WakeWordDetector(private val context: Context) {
    private val rate = 16000
    private var spotter: KeywordSpotter? = null
    @Volatile var isPaused = false

    private fun spotter(): KeywordSpotter {
        spotter?.let { return it }
        val b = "kws"
        val transducerCfg = OnlineTransducerModelConfig(
            encoder = "$b/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            decoder = "$b/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            joiner = "$b/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        )
        val modelCfg = OnlineModelConfig(
            transducer = transducerCfg,
            tokens = "$b/tokens.txt",
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
            modelConfig = modelCfg,
            keywordsFile = "$b/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f
        )
        return KeywordSpotter(assetManager = context.assets, config = cfg).also { spotter = it }
    }

    @SuppressLint("MissingPermission")
    suspend fun listenLoop(onWake: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val kws = try { spotter() } catch (_: Exception) { return@withContext }
        val st = kws.createStream()
        val bufSz = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(3200)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSz * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext
        val buf = ShortArray(1600)
        rec.startRecording()
        try {
            while (isActive) {
                if (isPaused) {
                    Thread.sleep(150)
                    continue
                }
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                st.acceptWaveform(FloatArray(n) { buf[it] / 32768.0f }, rate)
                while (kws.isReady(st)) {
                    kws.decode(st)
                    val kw = kws.getResult(st).keyword.uppercase()
                    if (kw.isNotBlank()) {
                        kws.reset(st)
                        onWake(if (kw.contains("FRIDAY")) "friday" else "jarvis")
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            st.release()
        }
    }

    fun release() {
        spotter?.release()
        spotter = null
    }
}
