package com.starkmesh.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

    var prompt by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var processActive by remember { mutableStateOf(false) }
    var processLabel by remember { mutableStateOf("IDLE // SYSTEM STANDBY") }
    var vStatus by remember {
        mutableStateOf(if (voices.areVoicesInstalled()) "Voices: Ready" else "Voices: System/Local Ready")
    }
    val msgs = remember { mutableStateListOf<Pair<String, String>>() }

    var devId by remember { mutableStateOf("vivo_v15_pro") }
    var pin by remember { mutableStateOf("") }
    var known by remember { mutableStateOf("vivo_v15_pro, oppo_a16e, raspberry_pi_5") }
    var apiUrl by remember { mutableStateOf("https://openrouter.ai/api/v1/chat/completions") }
    var backupApiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var backupApiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("deepseek/deepseek-v4.1-flash-abliterated, z-ai/glm-5.3-flash") }
    var visionModel by remember { mutableStateOf("qwen/qwen3-vl-235b-a22b-instruct") }
    var councilMode by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("45") }

    fun handleLocalCallOrLangCommand(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            lower.contains("hindi") || lower.contains("हिंदी") -> {
                voiceLang = "hi-IN"
                scope.launch { vault.putSecret("voice_lang", "hi-IN") }
                "भाषा हिंदी (hi-IN) पर सेट कर दी गई है।"
            }
            lower.contains("bengali") || lower.contains("bangla") || lower.contains("বাংলা") -> {
                voiceLang = "bn-IN"
                scope.launch { vault.putSecret("voice_lang", "bn-IN") }
                "ভাষা বাংলা (bn-IN) তে সেট করা হয়েছে।"
            }
            lower.contains("english") -> {
                voiceLang = "en-IN"
                scope.launch { vault.putSecret("voice_lang", "en-IN") }
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
                } else {
                    "Please specify a phone number to dial."
                }
            }
            else -> null
        }
    }

    fun sendQuery(t: String, b64: String? = null) {
        if (t.isBlank() || thinking) return
        msgs.add("YOU" to t)
        val localAction = if (b64 == null) handleLocalCallOrLangCommand(t) else null
        if (localAction != null) {
            msgs.add(persona.uppercase() to localAction)
            scope.launch {
                processActive = true
                processLabel = "VOICE SYNTH // SPEAKING RESPONSE"
                StarkRelayService.wakeDetectorRef?.isPaused = true
                voices.speak(localAction, persona)
                StarkRelayService.wakeDetectorRef?.isPaused = false
                processActive = false
                processLabel = "IDLE // SYSTEM STANDBY"
            }
            return
        }

        thinking = true
        processActive = true
        processLabel = "NEURAL CORE // GENERATING RESPONSE"
        val langInstruction = when (voiceLang) {
            "hi-IN" -> " Reply clearly in Hindi (Devanagari). "
            "bn-IN" -> " Reply clearly in Bengali (বাংলা). "
            else -> " Reply in English (or match Hindi/Bengali if spoken). "
        }
        scope.launch {
            StarkRelayService.wakeDetectorRef?.isPaused = true
            val r = ai.ask(langInstruction + t, persona, b64)
            msgs.add(persona.uppercase() to r)
            processLabel = "VOICE SYNTH // SPEAKING RESPONSE"
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
        devId = vault.getSecret("device_id", "vivo_v15_pro")
        pin = vault.getSecret("device_pin", "")
        known = vault.getSecret("known_devices", "vivo_v15_pro, oppo_a16e, raspberry_pi_5")
        apiUrl = vault.getSecret("api_url", "https://openrouter.ai/api/v1/chat/completions")
        backupApiUrl = vault.getSecret("backup_api_url", "")
        apiKey = vault.getSecret("api_key", "")
        backupApiKey = vault.getSecret("backup_api_key", "")
        model = vault.getSecret("model_name", "deepseek/deepseek-v4.1-flash-abliterated, z-ai/glm-5.3-flash")
        visionModel = vault.getSecret("vision_model_name", "qwen/qwen3-vl-235b-a22b-instruct")
        councilMode = vault.getSecret("council_mode", "false") == "true" 
        topic = vault.getSecret("relay_topic", "")
        token = vault.getSecret("relay_token", "")
        limit = vault.getSecret("screen_limit_min", "45")
        if (!voices.areVoicesInstalled()) {
            processActive = true
            processLabel = "VOICE ENGINE // INITIALIZING"
            voices.ensureBundledVoicesReady {
                vStatus = it
                processLabel = "VOICE ENGINE // ${it.uppercase()}"
            }
            delay(1500L)
            processActive = false
            processLabel = "IDLE // SYSTEM STANDBY"
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
        "friday" -> Color(0xFFFF5E8E)
        "edith" -> Color(0xFF69F0AE)
        else -> Color(0xFFFFB300)
    }
    val bg = Color(0xFF070D14)
    val pan = Color(0xFF0E1A27)

    Column(modifier = Modifier.fillMaxSize().background(bg).padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, acc, RoundedCornerShape(12.dp))
                .background(pan, RoundedCornerShape(12.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (persona) {
                        "friday" -> "F.R.I.D.A.Y. // ONLINE"
                        "edith" -> "E.D.I.T.H. // ONLINE"
                        else -> "J.A.R.V.I.S. // ONLINE"
                    },
                    color = acc,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("$devId • $voiceLang • $vStatus", color = Color(0xFF9BB4CA), fontSize = 11.sp)
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
        JarvisHologramOrb(persona = persona, isActive = thinking || processActive, sat = sat)
        Spacer(Modifier.height(6.dp))

        Column(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, if (processActive || thinking) acc else Color(0xFF1D354D), RoundedCornerShape(8.dp))
                .background(Color(0xFF09131F), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = processLabel,
                    color = if (processActive || thinking) acc else Color(0xFF8FB3D9),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (processActive || thinking) "ACTIVE" else "READY",
                    color = if (processActive || thinking) Color(0xFF69F0AE) else Color(0xFF5D7A96),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (processActive || thinking) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = acc,
                    trackColor = Color(0xFF16283B)
                )
            } else {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF1F4E78),
                    trackColor = Color(0xFF102030)
                )
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
                        .border(1.dp, acc, RoundedCornerShape(12.dp))
                        .background(pan, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { settingsSection = "vault" }) { Text("Vault & Voice") }
                        OutlinedButton(onClick = { settingsSection = "interconnect" }) {
                            Text("Interconnectivity", color = Color(0xFF00E5FF))
                        }
                    }

                    if (settingsSection == "interconnect") {
                        Text("BIXBY-STYLE INTERCONNECTIVITY & CALL ROUTING", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                handsFreeBg = !handsFreeBg
                                scope.launch { vault.putSecret("hands_free_bg", handsFreeBg.toString()) }
                            }) { Text(if (handsFreeBg) "Hands-Free BG: ON" else "Hands-Free BG: OFF", color = acc) }
                            OutlinedButton(onClick = {
                                autoAnswerCalls = !autoAnswerCalls
                                scope.launch { vault.putSecret("auto_answer_calls", autoAnswerCalls.toString()) }
                            }) { Text(if (autoAnswerCalls) "Auto-Answer: ON" else "Auto-Answer: OFF", color = acc) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    roleL.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
                                }
                            }) { Text("Set Default Phone/Call") }
                            OutlinedButton(onClick = {
                                permL.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
                            }) { Text("Call Perms") }
                        }
                        OutlinedTextField(value = known, onValueChange = { known = it }, label = { Text("Connected Mesh Nodes (Phones + Pi 5)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("Mesh Relay Topic (ntfy.sh)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Mesh Shared Secret Token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            scope.launch {
                                processActive = true
                                processLabel = "MESH SYNC // APPLYING INTERCONNECTIVITY"
                                vault.putSecret("known_devices", known.trim())
                                vault.putSecret("relay_topic", topic.trim())
                                vault.putSecret("relay_token", token.trim())
                                vault.putSecret("hands_free_bg", handsFreeBg.toString())
                                vault.putSecret("auto_answer_calls", autoAnswerCalls.toString())
                                ContextCompat.startForegroundService(ctx, Intent(ctx, StarkRelayService::class.java))
                                delay(900L)
                                processActive = false
                                processLabel = "IDLE // SYSTEM STANDBY"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Apply Interconnectivity & Sync Mesh") }
                    } else {
                        Text("LANGUAGE: EN / HINDI / BENGALI VOICE", color = acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { voiceLang = "en-IN"; scope.launch { vault.putSecret("voice_lang", "en-IN") } }) { Text("English") }
                            OutlinedButton(onClick = { voiceLang = "hi-IN"; scope.launch { vault.putSecret("voice_lang", "hi-IN") } }) { Text("हिंदी") }
                            OutlinedButton(onClick = { voiceLang = "bn-IN"; scope.launch { vault.putSecret("voice_lang", "bn-IN") } }) { Text("বাংলা") }
                        }
                        Text("VOICE PROFILES: J.A.R.V.I.S. / F.R.I.D.A.Y. / E.D.I.T.H. ($vStatus)", color = Color(0xFF69F0AE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = devId, onValueChange = { devId = it }, label = { Text("Device ID") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("Unlock PIN (Local Vault)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("Online LLM API URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = backupApiKey, onValueChange = { backupApiKey = it }, label = { Text("Backup API Key (Optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Abliterated / Reasoning Models (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = visionModel, onValueChange = { visionModel = it }, label = { Text("Cloud Vision Model (Camera/Photo)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            councilMode = !councilMode
                            scope.launch { vault.putSecret("council_mode", councilMode.toString()) }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (councilMode) "Multi-Model Accuracy Council: ON (Parallel Consensus)" else "Multi-Model Mode: Fast Failover Chain", color = Color(0xFF00E5FF))
                        }
                        OutlinedTextField(value = limit, onValueChange = { limit = it }, label = { Text("Screen Limit (min)") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            scope.launch {
                                processActive = true
                                processLabel = "VAULT // SAVING & STARTING SERVICE"
                                vault.putSecret("device_id", devId.trim())
                                vault.putSecret("device_pin", pin.trim())
                                vault.putSecret("known_devices", known.trim())
                                vault.putSecret("api_url", apiUrl.trim())
                                vault.putSecret("api_key", apiKey.trim())
                                vault.putSecret("model_name", model.trim())
                                vault.putSecret("relay_topic", topic.trim())
                                vault.putSecret("relay_token", token.trim())
                                vault.putSecret("screen_limit_min", limit.trim().ifBlank { "45" })
                                ContextCompat.startForegroundService(ctx, Intent(ctx, StarkRelayService::class.java))
                                delay(900L)
                                processActive = false
                                processLabel = "IDLE // SYSTEM STANDBY"
                                activeTab = "hud"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save Vault & Start Background Service") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
                                permL.launch(p.toTypedArray())
                            }) { Text("Mic/Cam/SMS/Call") }
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) roleL.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                            }) { Text("Call Screening") }
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Usage Access") }
                        }
                    }
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { persona = "jarvis"; scope.launch { vault.putSecret("active_persona", "jarvis") } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("J.A.R.V.I.S.", fontSize = 11.sp) }
                    Button(onClick = { persona = "friday"; scope.launch { vault.putSecret("active_persona", "friday") } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("F.R.I.D.A.Y.", fontSize = 11.sp) }
                    Button(onClick = { persona = "edith"; scope.launch { vault.putSecret("active_persona", "edith") } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("E.D.I.T.H.", fontSize = 11.sp) }
                    OutlinedButton(onClick = { busy = !busy; scope.launch { vault.putSecret("busy_mode", busy.toString()) } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (busy) "Busy:ON" else "Busy:OFF", color = acc, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val s = if (cam.isCameraHardwarePresent()) cam.captureFrontSnapshotBase64(owner) else null
                            sendQuery(if (s != null) "Inspect posture and visible fatigue cues for a brief wellness check." else "Camera unavailable; give a brief screen-break wellness check.", s)
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Wellness Check", color = acc, fontSize = 11.sp) }
                    OutlinedButton(onClick = { sendQuery("answer call") }) { Text("Answer Call", color = Color(0xFF69F0AE), fontSize = 11.sp) }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .border(1.dp, Color(0xFF1F354B), RoundedCornerShape(12.dp))
                        .background(pan, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(msgs) { (who, t) ->
                        Column {
                            Text(who, color = if (who == "YOU") Color(0xFF8FB3D9) else acc, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(t, color = Color(0xFFEAF6FF), fontSize = 14.sp)
                        }
                    }
                    if (thinking) item { Text("Processing...", color = acc, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("EN / हिंदी / বাংলা / Call...") },
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
