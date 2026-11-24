package com.starkmesh.app

import android.Manifest; import android.app.Activity; import android.app.role.RoleManager
import android.content.Intent; import android.os.Build; import android.os.Bundle; import android.provider.Settings; import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity; import androidx.activity.compose.rememberLauncherForActivityResult; import androidx.activity.compose.setContent; import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background; import androidx.compose.foundation.border; import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn; import androidx.compose.foundation.lazy.items; import androidx.compose.foundation.rememberScrollState; import androidx.compose.foundation.shape.RoundedCornerShape; import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.ui.Modifier; import androidx.compose.ui.graphics.Color; import androidx.compose.ui.platform.LocalContext; import androidx.compose.ui.platform.LocalLifecycleOwner; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.text.input.PasswordVisualTransformation; import androidx.compose.ui.unit.dp; import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat; import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var voices: NeuralVoiceEngine
    private val wakeNonce = mutableIntStateOf(0); private val wakePersona = mutableStateOf("jarvis")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voices = NeuralVoiceEngine(applicationContext)
        val vault = VaultManager(applicationContext); val ai = StarkAiClient(vault); val cam = CameraWellnessHelper(applicationContext)
        handleIntent(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { StarkHud(vault, ai, voices, cam, wakeNonce.intValue, wakePersona.value) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == StarkRelayService.ACTION_WAKE_TRIGGERED) { wakePersona.value = intent.getStringExtra(StarkRelayService.EXTRA_PERSONA) ?: "jarvis"; wakeNonce.intValue += 1 }
    }
    override fun onDestroy() { voices.release(); super.onDestroy() }
}

@Composable
fun StarkHud(vault: VaultManager, ai: StarkAiClient, voices: NeuralVoiceEngine, cam: CameraWellnessHelper, wakeNonce: Int, wakePersona: String) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current; val scope = rememberCoroutineScope()
    var persona by remember { mutableStateOf("jarvis") }; var busy by remember { mutableStateOf(false); var vaultOpen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }; var thinking by remember { mutableStateOf(false) }
    var vStatus by remember { mutableStateOf(if (voices.areVoicesInstalled()) "Voices: Offline Ready" else "Voices: Download in Vault") }
    val msgs = remember { mutableStateListOf<Pair<String, String>>() }

    var devId by remember { mutableStateOf("vivo_v15_pro") }; var pin by remember { mutableStateOf("") }; var known by remember { mutableStateOf("vivo_v15_pro, oppo_a16e, raspberry_pi_5") }
    var apiUrl by remember { mutableStateOf("") }; var apiKey by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }; var limit by remember { mutableStateOf("45") }

    fun sendQuery(t: String, b64: String? = null) {
        if (t.isBlank() || thinking) return
        msgs.add("YOU" to t); thinking = true
        scope.launch {
            StarkRelayService.wakeDetectorRef?.isPaused = true
            val r = ai.ask(t, persona, b64); msgs.add(persona.uppercase() to r); voices.speak(r, persona)
            StarkRelayService.wakeDetectorRef?.isPaused = false; thinking = false
        }
    }

    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val roleL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val micL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        StarkRelayService.wakeDetectorRef?.isPaused = false
        if (res.resultCode == Activity.RESULT_OK) res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sendQuery(it) }
    }
    fun launchMic() { StarkRelayService.wakeDetectorRef?.isPaused = true; try { micL.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } catch (_: Exception) { StarkRelayService.wakeDetectorRef?.isPaused = false } }

    LaunchedEffect(Unit) {
        persona = vault.getSecret("active_persona", "jarvis"); busy = vault.getSecret("busy_mode", "false") == "true"
        devId = vault.getSecret("device_id", "vivo_v15_pro"); pin = vault.getSecret("device_pin", ""); known = vault.getSecret("known_devices", "vivo_v15_pro, oppo_a16e, raspberry_pi_5")
        apiUrl = vault.getSecret("api_url", ""); apiKey = vault.getSecret("api_key", ""); model = vault.getSecret("model_name", "")
        topic = vault.getSecret("relay_topic", ""); token = vault.getSecret("relay_token", ""); limit = vault.getSecret("screen_limit_min", "45")
    }
    LaunchedEffect(wakeNonce) { if (wakeNonce > 0) { persona = wakePersona; vaultOpen = false; launchMic() } }

    val acc = if (persona == "friday") Color(0xFFFF5E8E) else Color(0xFF00E5FF)
    val bg = Color(0xFF070D14); val pan = Color(0xFF0E1A27)

    Column(modifier = Modifier.fillMaxSize().background(bg).padding(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, acc, RoundedCornerShape(12.dp)).background(pan, RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (persona == "friday") "F.R.I.D.A.Y. // ONLINE" else "J.A.R.V.I.S. // ONLINE", color = acc, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("$devId • $vStatus", color = Color(0xFF9BB4CA), fontSize = 11.sp)
            }
            OutlinedButton(onClick = { vaultOpen = !vaultOpen }) { Text(if (vaultOpen) "HUD" else "Vault", color = acc) }
        }
        Spacer(Modifier.height(10.dp))
        if (vaultOpen) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).border(1.dp, acc, RoundedCornerShape(12.dp)).background(pan, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Button(onClick = { scope.launch { voices.downloadAndInstallVoices { vStatus = it } } }, modifier = Modifier.fillMaxWidth()) { Text("Download Neural Voices (~134 MB)") }
                Text(vStatus, color = Color(0xFF9BB4CA), fontSize = 12.sp); Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = devId, onValueChange = { devId = it }, label = { Text("Device ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("Unlock PIN (Local Vault)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = known, onValueChange = { known = it }, label = { Text("Mesh Device IDs") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("Online LLM API URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("ntfy.sh Topic") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Relay Token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = limit, onValueChange = { limit = it }, label = { Text("Screen Limit (min)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        vault.putSecret("device_id", devId.trim()); vault.putSecret("device_pin", pin.trim()); vault.putSecret("known_devices", known.trim())
                        vault.putSecret("api_url", apiUrl.trim()); vault.putSecret("api_key", apiKey.trim()); vault.putSecret("model_name", model.trim())
                        vault.putSecret("relay_topic", topic.trim()); vault.putSecret("relay_token", token.trim()); vault.putSecret("screen_limit_min", limit.trim().ifBlank { "45" })
                        ContextCompat.startForegroundService(ctx, Intent(ctx, StarkRelayService::class.java)); vaultOpen = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save Vault & Start Service") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
                        permL.launch(p.toTypedArray())
                    }) { Text("Mic/Cam/SMS") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) roleL.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) }) { Text("Call Screening") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Usage Access") }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { persona = "jarvis"; scope.launch { vault.putSecret("active_persona", "jarvis") } }) { Text("J.A.R.V.I.S.") }
                Button(onClick = { persona = "friday"; scope.launch { vault.putSecret("active_persona", "friday") } }) { Text("F.R.I.D.A.Y.") }
                OutlinedButton(onClick = { busy = !busy; scope.launch { vault.putSecret("busy_mode", busy.toString()) } }) { Text(if (busy) "Busy: ON" else "Busy: OFF", color = acc) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val s = if (cam.isCameraHardwarePresent()) cam.captureFrontSnapshotBase64(owner) else null
                    sendQuery(if (s != null) "Inspect posture and visible fatigue cues for a brief wellness check." else "Camera unavailable; give a brief screen-break wellness check.", s)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Camera & Health Wellness Check", color = acc) }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, Color(0xFF1F354B), RoundedCornerShape(12.dp)).background(pan, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs) { (who, t) -> Column { Text(who, color = if (who == "YOU") Color(0xFF8FB3D9) else acc, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(t, color = Color(0xFFEAF6FF), fontSize = 14.sp) } }
                if (thinking) item { Text("Processing...", color = acc, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, placeholder = { Text("Say 'Hey Jarvis' / 'Hey Friday'...") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { launchMic() }) { Text("Mic") }
                Button(onClick = { val q = prompt; prompt = ""; sendQuery(q) }) { Text("Go") }
            }
        }
    }
}
