package com.starkmesh.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile

class JarvisInCallService : InCallService() {

    companion object {
        @Volatile var currentCall: Call? = null
        @Volatile var instance: JarvisInCallService? = null

        fun answerIncomingCall(context: Context): Boolean {
            val call = currentCall
            if (call != null && call.state == Call.STATE_RINGING) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                instance?.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                return true
            }
            return try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("MissingPermission")
                tm.acceptRingingCall()
                true
            } catch (_: Exception) {
                false
            }
        }

        fun hangupCurrentCall(): Boolean {
            val call = currentCall ?: return false
            call.disconnect()
            return true
        }

        fun placeOutgoingCall(context: Context, phoneOrUri: String): Boolean {
            val clean = phoneOrUri.filter { it.isDigit() || it == '+' }
            if (clean.isBlank()) return false
            return try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_DISCONNECTED) {
                if (currentCall == call) currentCall = null
            } else {
                currentCall = call
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        instance = this
        currentCall = call
        call.registerCallback(callCallback)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall == call) currentCall = null
    }

    override fun onDestroy() {
        instance = null
        currentCall = null
        super.onDestroy()
    }
}
