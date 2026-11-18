package com.example.pvzautoclicker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val lastClicks = mutableMapOf<Long, Long>()
    private var scanMs = 500L
    private val scanTask = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try { scanFrame() } catch (_: Exception) {}
            handler.postDelayed(this, scanMs)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data != null && projection == null) startCapture(code, data)
        return START_NOT_STICKY
    }
    private fun startCapture(code: Int, data: Intent) {
        createNotification()
        if (!OpenCVLoader.initDebug()) { stopSelf(); return }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture(); stopSelf() }
        }, handler)
        display = projection?.createVirtualDisplay("PVZCapture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        running.set(true)
        handler.post(scanTask)
    }
    private fun scanFrame() {
        val image = reader?.acquireLatestImage() ?: return
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap != null) {
            val rules = RuleStore.load(this).filter { it.enabled }
            for (rule in rules) {
                val now = System.currentTimeMillis()
                if (now - (lastClicks[rule.id] ?: 0L) < rule.cooldownMs) continue
                val match = matchTemplate(bitmap, rule.imagePath, rule.threshold) ?: continue
                val service = ClickAccessibilityService.instance ?: continue
                if (service.tap(match.first, match.second)) lastClicks[rule.id] = now
            }
            bitmap.recycle()
        }
    }
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val full = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        full.recycle()
        return cropped
    }
    private fun matchTemplate(screen: Bitmap, path: String, threshold: Double): Pair<Float, Float>? {
        val file = java.io.File(path)
        if (!file.exists()) return null
        val templateBitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        val src = Mat()
        val tpl = Mat()
        val result = Mat()
        try {
            Utils.bitmapToMat(screen, src)
            Utils.bitmapToMat(templateBitmap, tpl)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(tpl, tpl, Imgproc.COLOR_RGBA2GRAY)
            if (tpl.cols() > src.cols() || tpl.rows() > src.rows()) return null
            Imgproc.matchTemplate(src, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal < threshold) return null
            return Pair((mm.maxLoc.x + tpl.cols() / 2.0).toFloat(), (mm.maxLoc.y + tpl.rows() / 2.0).toFloat())
        } finally {
            src.release(); tpl.release(); result.release(); templateBitmap.recycle()
        }
    }
    private fun createNotification() {
        val channel = "pvz_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channel, "图片识别服务", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, channel)
            .setContentTitle("PVZ 图片自动点击正在运行")
            .setContentText("正在识别屏幕中的图片规则")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, n)
    }
    private fun stopCapture() {
        running.set(false)
        handler.removeCallbacks(scanTask)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    companion object {
        const val ACTION_STOP = "com.example.pvzautoclicker.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
