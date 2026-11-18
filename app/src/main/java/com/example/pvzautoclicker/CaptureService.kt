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
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private val running = AtomicBoolean(false)
    private val tapPending = AtomicBoolean(false)
    private var stepIndex = 0
    private var lastTapAt = 0L
    private var lastNoFrameLog = 0L
    private var projectId: String? = null
    private var scanMs = 350L

    private val scanTask = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try { scanFrame() } catch (e: Exception) { report("识别异常：${e.javaClass.simpleName}: ${e.message}") }
            if (running.get()) worker?.postDelayed(this, scanMs)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || code != Activity.RESULT_OK) { report("没有收到有效的屏幕录制授权"); stopSelf(); return START_NOT_STICKY }
        projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        try {
            createNotification()
            if (!OpenCVLoader.initDebug()) throw IllegalStateException("OpenCV 初始化失败")
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            workerThread = HandlerThread("PVZ-Capture-Worker").also { it.start() }
            worker = Handler(workerThread!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { report("系统已停止屏幕捕获"); stopCapture(); stopSelf() }
            }, mainHandler)
            display = projection?.createVirtualDisplay("PVZCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, worker)
            if (display == null) throw IllegalStateException("创建虚拟屏幕失败")
            stepIndex = 0
            running.set(true)
            report("屏幕捕获已启动：${width}x${height}")
            worker?.post(scanTask)
        } catch (e: Exception) {
            report("启动失败：${e.javaClass.simpleName}: ${e.message}")
            stopCapture(); stopSelf()
        }
    }

    private fun scanFrame() {
        val image = reader?.acquireLatestImage()
        if (image == null) {
            val now = System.currentTimeMillis()
            if (now - lastNoFrameLog > 5000) { report("等待屏幕帧…"); lastNoFrameLog = now }
            return
        }
        val bitmap = try { imageToBitmap(image) } finally { image.close() }
        if (bitmap == null) { report("屏幕帧转换失败"); return }
        try {
            val project = RuleStore.loadProjects(this).firstOrNull { it.id == projectId }
                ?: RuleStore.loadProjects(this).firstOrNull()
            if (project == null) { report("没有可用项目"); stopCapture(); return }
            val enabledSteps = project.steps.filter { it.enabled }
            if (enabledSteps.isEmpty()) { report("项目没有启用的步骤"); stopCapture(); return }
            if (stepIndex >= enabledSteps.size) {
                if (project.loop) stepIndex = 0 else { report("项目执行完成"); stopCapture(); return }
            }
            val step = enabledSteps[stepIndex]
            if (tapPending.get()) return
            val now = System.currentTimeMillis()
            val match = step.templates.filter { it.enabled && File(it.imagePath).isFile }
                .firstNotNullOfOrNull { template ->
                    if (now - lastTapAt < template.cooldownMs) null
                    else matchTemplate(bitmap, template.imagePath, template.threshold)?.let { Triple(it.first, it.second, template.name) }
                }
            if (match == null) {
                report("步骤 ${stepIndex + 1}/${enabledSteps.size}「${step.name}」：未匹配到图片")
                return
            }
            val service = ClickAccessibilityService.instance
            if (service == null) { report("识别成功，但无障碍服务未连接，无法点击"); return }
            if (!tapPending.compareAndSet(false, true)) return
            report("步骤 ${stepIndex + 1} 匹配「${match.third}」，坐标 (${match.first.toInt()}, ${match.second.toInt()})，准备点击")
            val accepted = service.tap(match.first, match.second) { completed ->
                tapPending.set(false)
                if (!running.get()) return@tap
                if (completed) {
                    lastTapAt = System.currentTimeMillis()
                    report("点击已完成：${match.third}")
                    val delay = step.waitAfterClickMs.coerceIn(0L, 30000L)
                    worker?.postDelayed({
                        if (running.get()) {
                            stepIndex++
                            report("进入下一步骤：${stepIndex + 1}")
                        }
                    }, delay)
                } else report("点击手势被系统取消；检查无障碍权限或当前窗口")
            }
            if (!accepted) { tapPending.set(false); report("系统拒绝提交点击手势") }
        } finally { bitmap.recycle() }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
        val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        full.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        full.recycle()
        return cropped
    }

    private fun matchTemplate(screen: Bitmap, path: String, threshold: Double): Pair<Float, Float>? {
        val templateBitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        val src = Mat(); val tpl = Mat(); val result = Mat()
        try {
            Utils.bitmapToMat(screen, src); Utils.bitmapToMat(templateBitmap, tpl)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(tpl, tpl, Imgproc.COLOR_RGBA2GRAY)
            if (tpl.empty() || tpl.cols() > src.cols() || tpl.rows() > src.rows()) return null
            Imgproc.matchTemplate(src, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val max = Core.minMaxLoc(result)
            if (!max.maxVal.isFinite() || max.maxVal < threshold) return null
            return Pair((max.maxLoc.x + tpl.cols() / 2.0).toFloat(), (max.maxLoc.y + tpl.rows() / 2.0).toFloat())
        } finally { src.release(); tpl.release(); result.release(); templateBitmap.recycle() }
    }

    private fun createNotification() {
        val channel = "pvz_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channel, "图片识别服务", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this, channel).setContentTitle("PVZ 自动化任务正在运行")
            .setContentText("正在按项目步骤识别屏幕").setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification)
    }

    private fun report(message: String) {
        sendBroadcast(Intent(ClickAccessibilityService.ACTION_STATUS).setPackage(packageName)
            .putExtra(ClickAccessibilityService.EXTRA_MESSAGE, message))
    }

    private fun stopCapture() {
        running.set(false); tapPending.set(false)
        worker?.removeCallbacksAndMessages(null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        workerThread?.quitSafely(); workerThread = null; worker = null
        report("自动化任务已停止")
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.example.pvzautoclicker.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
