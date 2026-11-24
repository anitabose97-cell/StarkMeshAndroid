package com.starkmesh.app

import android.os.Build; import android.telecom.Call; import android.telecom.CallScreeningService; import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope; import kotlinx.coroutines.Dispatchers; import kotlinx.coroutines.launch

class StarkCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val num = callDetails.handle?.schemeSpecificPart ?: return
        val vault = VaultManager(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            if (vault.getSecret("busy_mode", "false") != "true") { respondToCall(callDetails, CallResponse.Builder().build()); return@launch }
            respondToCall(callDetails, CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(false).build())
            val p = vault.getSecret("active_persona", "jarvis"); val rsn = vault.getSecret("busy_reason", "busy")
            val sms = StarkAiClient(vault).ask("One short SMS under 140 chars to $num: user cannot answer because $rsn, will call back. SMS text only.", p)
            try {
                val mgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applicationContext.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                mgr.sendTextMessage(num, null, sms.take(155), null, null)
            } catch (_: Exception) {}
        }
    }
}
