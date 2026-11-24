package com.starkmesh.app

import android.accessibilityservice.AccessibilityService; import android.accessibilityservice.GestureDescription
import android.content.Context; import android.graphics.Path; import android.os.Handler; import android.os.Looper; import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent; import android.view.accessibility.AccessibilityNodeInfo

class StarkAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: StarkAccessibilityService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun lockScreenNow(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    fun wakeAndUnlockWithPin(pin: String) {
        if (pin.isBlank()) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "StarkMesh:Wake")
        wl.acquire(6000L)
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ swipeUp() }, 650L)
        pin.forEachIndexed { i, d -> h.postDelayed({ clickLabel(d.toString()) }, 1450L + i * 280L) }
        h.postDelayed({ clickLabel("Enter") || clickLabel("OK") || clickLabel("Done") || clickLabel("Confirm") }, 1450L + pin.length * 280L + 250L)
    }

    private fun swipeUp() {
        val dm = resources.displayMetrics; val x = dm.widthPixels / 2f
        val p = Path().apply { moveTo(x, dm.heightPixels * 0.85f); lineTo(x, dm.heightPixels * 0.25f) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 260)).build(), null, null)
    }

    private fun clickLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(label)) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            var p = n.parent
            while (p != null) { if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p = p.parent }
        }
        return false
    }
}
