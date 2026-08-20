package com.example.humanoiddetector

import android.app.Notification
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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager

/**
 * Foreground service that captures the screen (MediaProjection) and runs
 * person detection on each frame. Results are drawn by [OverlayView].
 *
 * This service ONLY reads pixels and draws boxes. It never injects touches,
 * moves a cursor, or sends input to any app.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var overlay: OverlayView? = null
    private var detector: Detector? = null
    private var windowManager: WindowManager? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var lastFrameMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        detector = Detector(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "capture")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "capture",
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection could not be created")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Capture at 75% resolution to keep detection fast.
        val captureWidth = (screenWidth * 0.75f).toInt()
        val captureHeight = (screenHeight * 0.75f).toInt()

        captureThread = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight, PixelFormat.RGBA_8888, 2
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image)
            image.close()
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HumanoidDetector",
            captureWidth,
            captureHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        showOverlay()
        isRunning = true
    }

    private fun processImage(image: Image) {
        // Throttle to ~10 fps so detection stays cheap on-device.
        val now = System.currentTimeMillis()
        if (now - lastFrameMs < 100) return
        lastFrameMs = now

        val bitmap = imageToBitmap(image) ?: return
        val poses = detector?.detect(bitmap) ?: emptyList()

        // Map capture-space joints back to full screen coordinates.
        val scaleX = screenWidth.toFloat() / bitmap.width
        val scaleY = screenHeight.toFloat() / bitmap.height
        val mapped = poses.map { p ->
            val scaled = FloatArray(p.joints.size)
            for (i in 0 until p.joints.size step 2) {
                scaled[i] = p.joints[i] * scaleX
                scaled[i + 1] = p.joints[i + 1] * scaleY
            }
            PoseResult(scaled, p.visibility, p.emphasized)
        }
        overlay?.postPoses(mapped)
        bitmap.recycle()
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }

    private fun showOverlay() {
        val overlayView = OverlayView(this)
        overlay = overlayView
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlayView, params)
    }

    override fun onDestroy() {
        isRunning = false
        overlay?.let { windowManager?.removeView(it) }
        overlay = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        detector?.close()
        detector = null
        super.onDestroy()
    }
}
