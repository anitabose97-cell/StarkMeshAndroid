package com.starkmesh.app

import android.content.Context; import android.graphics.Bitmap; import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager; import android.util.Base64
import androidx.camera.core.*; import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat; import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream; import kotlin.coroutines.resume; import kotlin.coroutines.suspendCoroutine

class CameraWellnessHelper(private val context: Context) {
    fun isCameraHardwarePresent(): Boolean = try { (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.isNotEmpty() } catch (_: Exception) { false }

    suspend fun captureFrontSnapshotBase64(owner: LifecycleOwner): String? = suspendCoroutine { cont ->
        val fut = ProcessCameraProvider.getInstance(context); val ex = ContextCompat.getMainExecutor(context)
        fut.addListener({
            try {
                val prov = fut.get(); val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val sel = if (prov.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                prov.unbindAll(); prov.bindToLifecycle(owner, sel, cap)
                cap.takePicture(ex, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(img: ImageProxy) { val b = b64(img); img.close(); prov.unbindAll(); cont.resume(b) }
                    override fun onError(e: ImageCaptureException) { prov.unbindAll(); cont.resume(null) }
                })
            } catch (_: Exception) { cont.resume(null) }
        }, ex)
    }

    private fun b64(img: ImageProxy): String? = try {
        val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val h = ((bmp.height * 480) / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
        val out = ByteArrayOutputStream().also { Bitmap.createScaledBitmap(bmp, 480, h, true).compress(Bitmap.CompressFormat.JPEG, 70, it) }
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { null }
}
