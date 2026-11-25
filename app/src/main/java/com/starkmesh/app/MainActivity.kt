package com.starkmesh.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var voices: NeuralVoiceEngine
    private val wakeNonce = mutableIntStateOf(0)
    private val wakePersona = mutableStateOf("jarvis")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voices = NeuralVoiceEngine(applicationContext)
        val vault = VaultManager(applicationContext)
        val ai = StarkAiClient(vault)
        val cam = CameraWellnessHelper(applicationContext)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StarkHud(vault, ai, voices, cam, wakeNonce.intValue, wakePersona.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == StarkRelayService.ACTION_WAKE_TRIGGERED) {
            wakePersona.value = intent.getStringExtra(StarkRelayService.EXTRA_PERSONA) ?: "jarvis"
            wakeNonce.intValue += 1
        }
    }

    override fun onDestroy() {
        voices.release()
        super.onDestroy()
    }
}

@Composable
fun StarkHud(
    vault: VaultManager,
    ai: StarkAiClient,
    voices: NeuralVoiceEngine,
    cam: CameraWellnessHelper,
    wakeNonce: Int,
    wakePersona: String
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sat = rememberSatelliteTelemetry()

    var persona by remember { mutableStateOf("jarvis") }
    var busy by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf("hud") }
    var settingsSection by remember { mutableStateOf("vault") }
    var voiceLang by remember { mutableStateOf("en-IN") }
    var handsFreeBg by remember { mutableStateOf(true) }
    var autoAnswerCalls by remember { mutableStateOf(false) }
    var pendingRecordVideo by remember { mutableStateOf(false) }
    var weatherText by remember { mutableStateOf("Weather: Syncing...") }

    var prompt by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var processActive by remember { mutableStateOf(false) }
    var processLabel by remember { mutableStateOf("IDLE // SYSTEM STANDBY") }
    var vStatus by remember {
        mutableStateOf(if (voices.areVoicesInstalled()) "Voices: Ready" else "Voices: Persona TTS Ready")
    }
    val msgs = remember { mutableStateListOf<Pair<String, String>>() }

    var devId by remember { mutableStateOf("peer_android_node") }
    var pin by remember { mutableStateOf("") }
    var known by remember { mutableStateOf("vivo_v15_pro, oppo_a16e, realme_11_pro_plus") }
    var apiUrl by remember { mutableStateOf(StarkAiClient.DEFAULT_API_URL) }
    var backupApiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var backupApiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(StarkAiClient.DEFAULT_COUNCIL_MODELS) }
    var visionModel by remember { mutableStateOf(StarkAiClient.DEFAULT_VISION_MODEL) }
    var councilMode by remember { mutableStateOf(true) }
    var topic by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    var newMemText by remember { mutableStateOf("") }
    var passSite by remember { mutableStateOf("") }
    var passSecret by remember { mutableStateOf("") }

    val screenProjL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val svc = Intent(ctx, JarvisScreenService::class.java).apply {
                action = JarvisScreenService.ACTION_START
                putExtra(JarvisScreenService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(JarvisScreenService.EXTRA_RESULT_DATA, res.data)
                putExtra(JarvisScreenService.EXTRA_RECORD_VIDEO, pendingRecordVideo)
            }
            ContextCompat.startForegroundService(ctx, svc)
            msgs.add(persona.uppercase() to if (pendingRecordVideo) "Screen recording started to Movies/." else "Live screen vision stream active.")
        }
    }

    fun requestScreenProjection(recordVideo: Boolean) {
        pendingRecordVideo = recordVideo
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenProjL.launch(mgr.createScreenCaptureIntent())
    }

    suspend fun handleLocalCommand(raw: String): String? {
        val lower = raw.lowercase()
        JarvisMemoryAndControl.executeDeviceControl(ctx, raw)?.let { return it }

        return when {
            lower.startsWith("remember ") || lower.startsWith("i like ") || lower.startsWith("my favorite ") -> {
                JarvisMemoryAndControl.rememberFact(vault, raw)
                "Saved to long-term memory: \"$raw\""
            }
            lower.contains("what do you remember") || lower.contains("my preferences") -> {
                val list = JarvisMemoryAndControl.getMemoryList(vault)
                if (list.isEmpty()) "Memory bank is currently empty." else "Stored memories:\n- " + list.joinToString("\n- ")
            }
            lower.startsWith("save password ") -> {
                val parts = raw.removePrefix("save password ").trim().split(" ", limit = 2)
                if (parts.size == 2) {
                    JarvisMemoryAndControl.savePasswordEntry(vault, parts[0], parts[1])
                    "Encrypted credential saved locally for '${parts[0]}'."
                } else "Format: save password <site> <password>"
            }
            lower.contains("password for ") || lower.contains("my password") -> {
                val target = lower.substringAfter("password for ", "").trim()
                val map = JarvisMemoryAndControl.getSavedPasswords(vault)
                if (target.isNotBlank() && map.containsKey(target)) {
                    "Encrypted vault entry for $target: ${map[target]}"
                } else if (map.isNotEmpty()) {
                    "Saved password keys: ${map.keys.joinToString(", ")}. Ask 'password for <name>' to reveal."
                } else "No passwords stored yet. Add them in Settings > Vault or say 'save password gmail mypass'."
            }
            lower.contains("weather") || lower.contains("मौसम") || lower.contains("আবহাওয়া") -> {
                val w = JarvisMemoryAndControl.fetchLiveWeather(sat.lat, sat.lon)
                weatherText = w
                w
            }
            lower.contains("stop screen") || lower.contains("stop recording") -> {
                ctx.startService(Intent(ctx, JarvisScreenService::class.java).apply { action = JarvisScreenService.ACTION_STOP })
                "Screen access & recording stopped. Last file: ${JarvisScreenService.lastRecordingPath ?: "none"}"
            }
            lower.contains("record screen") -> {
                requestScreenProjection(recordVideo = true)
                "Requesting screen recording permission..."
            }
            lower.contains("live screen") || lower.contains("share screen") -> {
                requestScreenProjection(recordVideo = false)
                "Requesting live screen vision permission..."
            }
            lower.contains("find file") || lower.contains("private files") || lower.contains("my files") -> {
                val q = lower.substringAfter("find file", "").trim()
                val files = JarvisMemoryAndControl.scanPrivateFiles(q.ifBlank { null }, 8)
                if (files.isEmpty()) "No matching private files found."
                else "Found ${files.size} files:\n" + files.joinToString("\n") { "• ${it.name}" }
            }
            lower.contains("hindi") || lower.contains("हिंदी") -> {
                voiceLang = "hi-IN"; vault.putSecret("voice_lang", "hi-IN")
                "भाषा हिंदी (hi-IN) पर सेट कर दी गई है।"
            }
            lower.contains("bengali") || lower.contains("bangla") || lower.contains("বাংলা") -> {
                voiceLang = "bn-IN"; vault.putSecret("voice_lang", "bn-IN")
                "ভাষা বাংলা (bn-IN) তে সেট করা হয়েছে।"
            }
            lower.contains("english") -> {
                voiceLang = "en-IN"; vault.putSecret("voice_lang", "en-IN")
                "Language switched to English (en-IN)."
            }
            lower.contains("answer call") || lower.contains("pick up") || lower.contains("कॉल उठाओ") || lower.contains("কল ধরো") -> {
                if (JarvisInCallService.answerIncomingCall(ctx)) "Incoming call answered." else "No ringing incoming call detected."
            }
            lower.contains("hang up") || lower.contains("end call") || lower.contains("कॉल काटो") || lower.contains("কল কাটো") -> {
                if (JarvisInCallService.hangupCurrentCall()) "Call disconnected." else "No active call to end."
            }
            lower.contains("call ") || lower.contains("dial ") || lower.contains("कॉल करो") || lower.contains("কল করো") -> {
                val num = raw.filter { it.isDigit() || it == '+' }
                if (num.length >= 3) {
                    JarvisInCallService.placeOutgoingCall(ctx, num)
                    "Calling $num now."
                } else "Please specify a phone number to dial."
            }
            else -> null
        }
    }

    fun sendQuery(t: String, b64: String? = null) {
        if (t.isBlank() || thinking) return
        msgs.add("YOU" to t)
        scope.launch {
            val localAction = if (b64 == null) handleLocalCommand(t) else null
            if (localAction != null) {
                msgs.add(persona.uppercase() to localAction)
                processActive = true
                processLabel = "VOICE SYNTH // SPEAKING"
                StarkRelayService.wakeDetectorRef?.isPaused = true
                voices.speak(localAction, persona)
                StarkRelayService.wakeDetectorRef?.isPaused = false
                processActive = false
                processLabel = "IDLE // SYSTEM STANDBY"
                return@launch
            }

            thinking = true
            processActive = true
            processLabel = "CONSCIOUSNESS COUNCIL // THINKING"
            val attachedImg = b64 ?: JarvisScreenService.latestScreenBase64
            val langInstruction = when (voiceLang) {
                "hi-IN" -> " Reply clearly in Hindi (Devanagari). "
                "bn-IN" -> " Reply clearly in Bengali (বাংলা). "
                else -> " Reply clearly in English (or match Hindi/Bengali if spoken). "
            }
            StarkRelayService.wakeDetectorRef?.isPaused = true
            val r = ai.ask(langInstruction + t, persona, attachedImg)
            msgs.add(persona.uppercase() to r)
            processLabel = "VOICE SYNTH // SPEAKING"
            voices.speak(r, persona)
            StarkRelayService.wakeDetectorRef?.isPaused = false
            thinking = false
            processActive = false
            processLabel = "IDLE // SYSTEM STANDBY"
        }
    }

    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val roleL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val micL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        StarkRelayService.wakeDetectorRef?.isPaused = false
        processActive = false
        processLabel = "IDLE // SYSTEM STANDBY"
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { sendQuery(it) }
        }
    }

    fun launchMic() {
        processActive = true
        processLabel = "MIC CAPTURE // LISTENING ($voiceLang)"
        StarkRelayService.wakeDetectorRef?.isPaused = true
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLang)
                .putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("en-IN", "hi-IN", "bn-IN"))
            micL.launch(intent)
        } catch (_: Exception) {
            StarkRelayService.wakeDetectorRef?.isPaused = false
            processActive = false
            processLabel = "IDLE // SYSTEM STANDBY"
        }
    }

    LaunchedEffect(Unit) {
        persona = vault.getSecret("active_persona", "jarvis")
        busy = vault.getSecret("busy_mode", "false") == "true"
        voiceLang = vault.getSecret("voice_lang", "en-IN")
        handsFreeBg = vault.getSecret("hands_free_bg", "true") == "true"
        autoAnswerCalls = vault.getSecret("auto_answer_calls", "false") == "true"
        devId = vault.getSecret("device_id", Build.MODEL.lowercase().replace(" ", "_"))
        pin = vault.getSecret("device_pin", "")
        known = vault.getSecret("known_devices", "vivo_v15_pro, oppo_a16e, realme_11_pro_plus")
        apiUrl = vault.getSecret("api_url", StarkAiClient.DEFAULT_API_URL)
        backupApiUrl = vault.getSecret("backup_api_url", "")
        apiKey = vault.getSecret("api_key", "")
        backupApiKey = vault.getSecret("backup_api_key", "")
        model = vault.getSecret("model_name", StarkAiClient.DEFAULT_COUNCIL_MODELS)
        visionModel = vault.getSecret("vision_model_name", StarkAiClient.DEFAULT_VISION_MODEL)
        councilMode = vault.getSecret("council_mode", "true") == "true"
        topic = vault.getSecret("relay_topic", "")
        token = vault.getSecret("relay_token", "")

        // Start always-on foreground mic & mesh sentinel
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, StarkRelayService::class.java))
        } catch (_: Exception) {}

        weatherText = JarvisMemoryAndControl.fetchLiveWeather(sat.lat, sat.lon)
        if (msgs.isEmpty()) {
            val greet = JarvisMemoryAndControl.timeGreeting("Souparna")
            msgs.add(persona.uppercase() to "$greet $weatherText")
            voices.speak(greet, persona)
        }
    }

    LaunchedEffect(wakeNonce) {
        if (wakeNonce > 0) {
            persona = wakePersona
            activeTab = "hud"
            launchMic()
        }
    }

    val acc = when (persona) {
        "friday" -> Color(0xFFFF4081)
        "edith" -> Color(0xFF00E676)
        else -> Color(0xFFFFB300)
    }
    val bgBrush = Brush.verticalGradient(
        listOf(acc.copy(alpha = 0.16f), Color(0xFF050A12), Color(0xFF03070D))
    )
    val pan = Color(0xFF0B1522)

    Column(modifier = Modifier.fillMaxSize().background(bgBrush).padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, acc.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                .background(pan, RoundedCornerShape(14.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (persona) {
                        "friday" -> "F.R.I.D.A.Y. // OMNI-OS"
                        "edith" -> "E.D.I.T.H. // OMNI-OS"
                        else -> "J.A.R.V.I.S. // OMNI-OS"
                    },
                    color = acc,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("$devId • $voiceLang • $vStatus", color = Color(0xFF9BB4CA), fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { activeTab = "hud" }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("HUD", color = if (activeTab == "hud") acc else Color(0xFF9BB4CA), fontSize = 11.sp)
                }
                OutlinedButton(onClick = { activeTab = "sat" }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Sat Live", color = if (activeTab == "sat") Color(0xFF00E5FF) else Color(0xFF9BB4CA), fontSize = 11.sp)
                }
                OutlinedButton(onClick = { activeTab = "settings" }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Settings", color = if (activeTab == "settings") acc else Color(0xFF9BB4CA), fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        JarvisHologramOrb(persona = persona, isActive = thinking || processActive, sat = sat, weatherSummary = weatherText)
        Spacer(Modifier.height(6.dp))

        // Live Process Status Bar
        Column(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, if (processActive || thinking) acc else Color(0xFF1D354D), RoundedCornerShape(8.dp))
                .background(Color(0xFF08111C), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(processLabel, color = if (processActive || thinking) acc else Color(0xFF8FB3D9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(if (processActive || thinking) "ACTIVE" else "ALWAYS-ON MIC", color = Color(0xFF69F0AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (processActive || thinking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = acc, trackColor = Color(0xFF16283B))
            } else {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFF1F4E78), trackColor = Color(0xFF102030))
            }
        }
        Spacer(Modifier.height(6.dp))

        when (activeTab) {
            "sat" -> {
                SatelliteAccessLiveView(persona = persona, onBackToHud = { activeTab = "hud" })
            }
            "settings" -> {
                Column(
                    modifier = Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .border(1.dp, acc, RoundedCornerShape(14.dp))
                        .background(pan, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { settingsSection = "vault" }) { Text("Models & Passwords", fontSize = 11.sp) }
                        OutlinedButton(onClick = { settingsSection = "interconnect" }) {
                            Text("Control & Mesh", color = Color(0xFF00E5FF), fontSize = 11.sp)
                        }
                    }

                    if (settingsSection == "interconnect") {
                        Text("200% DEVICE CONTROL, SCREEN ACCESS & INTERCONNECTIVITY", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { requestScreenProjection(false) }) { Text("Live Screen Vision", fontSize = 11.sp) }
                            OutlinedButton(onClick = { requestScreenProjection(true) }) { Text("Record Screen MP4", fontSize = 11.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                                } else {
                                    permL.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                                }
                            }) { Text("All Private Files", fontSize = 11.sp) }
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    roleL.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
                                }
                            }) { Text("Default Phone/Call", fontSize = 11.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility Control", fontSize = 11.sp) }
                            OutlinedButton(onClick = {
                                permL.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.ACCESS_FINE_LOCATION))
                            }) { Text("Grant All Perms", fontSize = 11.sp) }
                        }
                        OutlinedTextField(value = known, onValueChange = { known = it }, label = { Text("Peer Mesh Devices") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("Mesh Relay Topic (ntfy.sh)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Mesh Token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            scope.launch {
                                vault.putSecret("known_devices", known.trim())
                                vault.putSecret("relay_topic", topic.trim())
                                vault.putSecret("relay_token", token.trim())
                                ContextCompat.startForegroundService(ctx, Intent(ctx, StarkRelayService::class.java))
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Sync Interconnected Mesh") }
                    } else {
                        Text("LANGUAGE: EN / HINDI / BENGALI", color = acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { voiceLang = "en-IN"; scope.launch { vault.putSecret("voice_lang", "en-IN") } }) { Text("English") }
                            OutlinedButton(onClick = { voiceLang = "hi-IN"; scope.launch { vault.putSecret("voice_lang", "hi-IN") } }) { Text("हिंदी") }
                            OutlinedButton(onClick = { voiceLang = "bn-IN"; scope.launch { vault.putSecret("voice_lang", "bn-IN") } }) { Text("বাংলা") }
                        }

                        Text("PRE-LOADED CLOUD ABLITERATED COUNCIL", color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("Cloud API URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Cloud API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Council Models (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = visionModel, onValueChange = { visionModel = it }, label = { Text("Vision Model (Screen & Camera)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            councilMode = !councilMode
                            scope.launch { vault.putSecret("council_mode", councilMode.toString()) }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (councilMode) "Multi-Model Accuracy Council: ON" else "Fast Failover Mode: ON", color = Color(0xFF00E5FF))
                        }

                        Text("ENCRYPTED PASSWORD VAULT & LONG-TERM MEMORY", color = acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = passSite, onValueChange = { passSite = it }, label = { Text("App/Site") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = passSecret, onValueChange = { passSecret = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                        }
                        Button(onClick = {
                            scope.launch {
                                if (passSite.isNotBlank() && passSecret.isNotBlank()) {
                                    JarvisMemoryAndControl.savePasswordEntry(vault, passSite, passSecret)
                                    passSite = ""; passSecret = ""
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save Password to Encrypted Vault") }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = newMemText, onValueChange = { newMemText = it }, label = { Text("Add Preference / Memory") }, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                scope.launch {
                                    if (newMemText.isNotBlank()) {
                                        JarvisMemoryAndControl.rememberFact(vault, newMemText)
                                        newMemText = ""
                                    }
                                }
                            }) { Text("Save") }
                        }

                        Button(onClick = {
                            scope.launch {
                                vault.putSecret("device_id", devId.trim())
                                vault.putSecret("api_url", apiUrl.trim())
                                vault.putSecret("api_key", apiKey.trim())
                                vault.putSecret("model_name", model.trim())
                                vault.putSecret("vision_model_name", visionModel.trim())
                                vault.putSecret("council_mode", councilMode.toString())
                                ContextCompat.startForegroundService(ctx, Intent(ctx, StarkRelayService::class.java))
                                activeTab = "hud"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save All & Activate Consciousness") }
                    }
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(onClick = { persona = "jarvis"; scope.launch { vault.putSecret("active_persona", "jarvis") } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("J.A.R.V.I.S.", fontSize = 10.sp) }
                    Button(onClick = { persona = "friday"; scope.launch { vault.putSecret("active_persona", "friday") } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("F.R.I.D.A.Y.", fontSize = 10.sp) }
                    Button(onClick = { persona = "edith"; scope.launch { vault.putSecret("active_persona", "edith") } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("E.D.I.T.H.", fontSize = 10.sp) }
                    OutlinedButton(onClick = { requestScreenProjection(false) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (JarvisScreenService.isRunning) "Screen:ON" else "Screen", color = Color(0xFF00E5FF), fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val s = if (cam.isCameraHardwarePresent()) cam.captureFrontSnapshotBase64(owner) else null
                            sendQuery(if (s != null) "Inspect live camera view and give a helpful update." else "Analyze my live screen or give a status update.", s)
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Cam / Screen Vision", color = acc, fontSize = 10.sp) }
                    OutlinedButton(onClick = { sendQuery("weather") }) { Text("Weather", color = Color(0xFF00E5FF), fontSize = 10.sp) }
                    OutlinedButton(onClick = { sendQuery("answer call") }) { Text("Answer", color = Color(0xFF69F0AE), fontSize = 10.sp) }
                }
                Spacer(Modifier.height(6.dp))

                // Gemini / ChatGPT-style conversational cards
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .border(1.dp, Color(0xFF1C3247), RoundedCornerShape(14.dp))
                        .background(Color(0xFF070E18), RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(msgs) { (who, t) ->
                        val isUser = who == "YOU"
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(
                                    if (isUser) Color(0xFF13263B) else pan,
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isUser) Color(0xFF2B4C6F) else acc.copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(who, color = if (isUser) Color(0xFF8FB3D9) else acc, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(t, color = Color(0xFFF0F7FF), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                    if (thinking) {
                        item {
                            Text("● Consciousness Council synthesizing response...", color = acc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("Ask anything, EN/HI/BN, save password...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = { launchMic() }) { Text("Mic") }
                    Button(onClick = { val q = prompt; prompt = ""; sendQuery(q) }) { Text("Go") }
                }
            }
        }
    }
}
