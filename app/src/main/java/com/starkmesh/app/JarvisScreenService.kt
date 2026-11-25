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
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File

class JarvisScreenService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_screen_channel"
        const val ACTION_START = "com.starkmesh.app.SCREEN_START"
        const val ACTION_STOP = "com.starkmesh.app.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RECORD_VIDEO = "record_video"

        @Volatile var isRunning = false
        @Volatile var isRecordingVideo = false
        @Volatile var latestScreenBase64: String? = null
        @Volatile var lastRecordingPath: String? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Live Screen & Vision")
            .setContentText("Screen access and recording active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(4042, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(4042, notif)
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
            cleanup()
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup()
                    stopSelf()
                }
            }, null)

            val metrics = resources.displayMetrics
            val w = 720
            val h = ((metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()) * w).toInt().coerceAtLeast(1280)
            val dpi = metrics.densityDpi

            if (recordVideo) {
                startVideoRecording(w, h, dpi)
            } else {
                startLiveScreenReader(w, h, dpi)
            }
            isRunning = true
        }
        return START_NOT_STICKY
    }

    private fun startLiveScreenReader(w: Int, h: Int, dpi: Int) {
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "JarvisLiveScreen", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 65, out)
                latestScreenBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                bmp.recycle()
                cropped.recycle()
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, null)
    }

    private fun startVideoRecording(w: Int, h: Int, dpi: Int) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .apply { mkdirs() }
        val file = File(dir, "JarvisScreen_${System.currentTimeMillis()}.mp4")
        lastRecordingPath = file.absolutePath

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(file.absolutePath)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(w, h)
        mr.setVideoFrameRate(24)
        mr.setVideoEncodingBitRate(3_500_000)
        mr.prepare()

        virtualDisplay = projection?.createVirtualDisplay(
            "JarvisScreenRec", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mr.surface, null, null
        )
        mr.start()
        recorder = mr
        isRecordingVideo = true
    }

    private fun cleanup() {
        try { if (isRecordingVideo) recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecordingVideo = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        isRunning = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jarvis Screen Access", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
