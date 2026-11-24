package com.starkmesh.app

import android.Manifest; import android.app.*; import android.content.*; import android.content.pm.PackageManager; import android.content.pm.ServiceInfo
import android.os.Build; import android.os.IBinder
import androidx.core.app.ServiceCompat; import androidx.core.content.ContextCompat
import kotlinx.coroutines.*; import okhttp3.OkHttpClient; import okhttp3.Request; import org.json.JSONObject
import java.io.BufferedReader; import java.io.InputStreamReader; import java.util.concurrent.TimeUnit

class StarkRelayService : Service() {
    companion object {
        const val ACTION_WAKE_TRIGGERED = "com.starkmesh.app.WAKE_TRIGGERED"; const val EXTRA_PERSONA = "persona"
        @Volatile var wakeDetectorRef: WakeWordDetector? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vault: VaultManager; private lateinit var voices: NeuralVoiceEngine; private lateinit var wake: WakeWordDetector
    private var onSince = System.currentTimeMillis(); private var screenOn = true; private var warned = false

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; onSince = System.currentTimeMillis(); warned = false }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; warned = false }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        vault = VaultManager(applicationContext); voices = NeuralVoiceEngine(applicationContext); wake = WakeWordDetector(applicationContext).also { wakeDetectorRef = it }
        startFg(); registerReceiver(rx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        relayLoop(); screenLoop(); wakeLoop()
    }

    private fun startFg() {
        val ch = "starkmesh_core"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "StarkMesh", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("StarkMesh Online").setContentText("Wake word & relay active").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) (if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else 0
        ServiceCompat.startForeground(this, 1001, n, t)
    }

    private fun wakeLoop() = scope.launch {
        if (ContextCompat.checkSelfPermission(this@StarkRelayService, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@launch
        wake.listenLoop { p ->
            wake.isPaused = true; vault.putSecret("active_persona", p)
            voices.speak(if (p == "friday") "Hey there, I'm listening!" else "At your service, sir.", p)
            startActivity(Intent(this@StarkRelayService, MainActivity::class.java).apply { action = ACTION_WAKE_TRIGGERED; putExtra(EXTRA_PERSONA, p); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) })
            delay(5000L); wake.isPaused = false
        }
    }

    private fun relayLoop() = scope.launch {
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        while (isActive) {
            try {
                val topic = vault.getSecret("relay_topic"); val tok = vault.getSecret("relay_token"); val me = vault.getSecret("device_id", "vivo_v15_pro")
                if (topic.isBlank()) { delay(5000); continue }
                http.newCall(Request.Builder().url("https://ntfy.sh/$topic/json").build()).execute().use { r ->
                    val br = BufferedReader(InputStreamReader(r.body!!.byteStream())); var line: String?
                    while (br.readLine().also { line = it } != null && isActive) {
                        val env = JSONObject(line ?: continue); if (env.optString("event") != "message") continue
                        val cmd = JSONObject(env.optString("message", "{}")); if (cmd.optString("token") != tok || cmd.optString("target") != me) continue
                        val p = vault.getSecret("active_persona", "jarvis")
                        when (cmd.optString("action")) {
                            "lock" -> StarkAccessibilityService.instance?.lockScreenNow()
                            "unlock" -> StarkAccessibilityService.instance?.wakeAndUnlockWithPin(vault.getSecret("device_pin"))
                            "status" -> { wake.isPaused = true; voices.speak("$me online.", p); wake.isPaused = false }
                        }
                    }
                }
            } catch (_: Exception) { delay(4000) }
        }
    }

    private fun screenLoop() = scope.launch {
        while (isActive) {
            delay(30_000L); if (!screenOn) continue
            val lim = vault.getSecret("screen_limit_min", "45").toLongOrNull() ?: 45L
            val mins = (System.currentTimeMillis() - onSince) / 60_000L; val p = vault.getSecret("active_persona", "jarvis")
            if (mins >= (lim * 0.8).toLong() && !warned) {
                warned = true; wake.isPaused = true
                voices.speak(if (p == "friday") "Hey, $mins minutes straight—blink and stretch before I lock you out!" else "Advisory: $mins minutes unbroken screen time. Rest your eyes.", p)
                wake.isPaused = false
            } else if (mins >= lim) {
                wake.isPaused = true
                voices.speak(if (p == "friday") "Break time—locking your screen!" else "Screen limit reached. Locking.", p)
                StarkAccessibilityService.instance?.lockScreenNow(); screenOn = false; wake.isPaused = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { unregisterReceiver(rx); scope.cancel(); wake.release(); wakeDetectorRef = null; voices.release(); super.onDestroy() }
}
