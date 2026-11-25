package com.starkmesh.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.starkmesh.app.SCREEN_START"
        const val ACTION_STOP = "com.starkmesh.app.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RECORD_VIDEO = "record_video"

        @Volatile var isRunning = false
        @Volatile var isRecordingVideo = false
        @Volatile var latestScreenBase64: String? = null
        @Volatile var lastSavedVideoPath: String? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProjection()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notif = NotificationCompat.Builder(this, "jarvis_screen")
            .setContentTitle("Jarvis Live Screen Vision")
            .setContentText("Live screen access & recording active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(202, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(202, notif)
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val recordVideo = intent?.getBooleanExtra(EXTRA_RECORD_VIDEO, false) ?: false

        if (code == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopProjection()
                }
            }, handler)
            setupVirtualDisplay(recordVideo)
            isRunning = true
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay(recordVideo: Boolean) {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels / 2).coerceAtLeast(540)
        val h = (dm.heightPixels / 2).coerceAtLeast(960)
        val dpi = dm.densityDpi

        if (recordVideo) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Jarvis").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "Jarvis_Rec_$ts.mp4")
            lastSavedVideoPath = outFile.absolutePath

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(outFile.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(24)
            rec.setVideoEncodingBitRate(3_000_000)
            rec.prepare()

            virtualDisplay = projection?.createVirtualDisplay(
                "JarvisRecordDisplay", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, handler
            )
            rec.start()
            mediaRecorder = rec
            isRecordingVideo = true
        } else {
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameMs > 1200L) {
                    lastFrameMs = now
                    try {
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w
                        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(buf)
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                        val baos = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                        latestScreenBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        bmp.recycle()
                        cropped.recycle()
                    } catch (_: Exception) {}
                }
                img.close()
            }, handler)

            virtualDisplay = projection?.createVirtualDisplay(
                "JarvisVisionDisplay", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            imageReader = reader
            isRecordingVideo = false
        }
    }

    private fun stopProjection() {
        try {
            if (isRecordingVideo) {
                mediaRecorder?.stop()
            }
        } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        isRunning = false
        isRecordingVideo = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("jarvis_screen", "Jarvis Screen Access", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }
}
