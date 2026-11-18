package com.example.pvzautoclicker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val rules = mutableListOf<ClickRule>()
    private val picker = 1001
    private val captureRequest = 1002
    private val projectionManager by lazy { getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules.addAll(RuleStore.load(this))
        buildUi()
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val title = TextView(this).apply { text = "植物大战僵尸2 · 图片自动点击"; textSize = 21f }
        status = TextView(this).apply { text = "状态：未运行（先开启无障碍服务）"; textSize = 14f; setPadding(0, 16, 0, 16) }
        val add = Button(this).apply { text = "添加识别图片"; setOnClickListener { chooseImage() } }
        val accessibility = Button(this).apply {
            text = "开启无障碍权限"
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val start = Button(this).apply { text = "开始识别"; setOnClickListener { startCapture() } }
        val stop = Button(this).apply { text = "停止识别"; setOnClickListener { stopCapture() } }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(title); root.addView(status); root.addView(accessibility); root.addView(add)
        root.addView(start); root.addView(stop)
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderRules()
    }
    private fun chooseImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(i, picker)
    }
    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
            status.text = "状态：识别运行中"
        }
        if (requestCode == picker && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val name = "template_${System.currentTimeMillis()}.png"
                val dest = File(RuleStore.templatesDir(this), name)
                contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                val ruleName = "规则 ${rules.size + 1}"
                rules.add(ClickRule(System.currentTimeMillis(), ruleName, dest.absolutePath))
                RuleStore.save(this, rules)
                renderRules()
            } catch (e: Exception) { Toast.makeText(this, "导入图片失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    private fun startCapture() {
        if (ClickAccessibilityService.instance == null) {
            Toast.makeText(this, "请先开启本应用的无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (rules.none { it.enabled }) {
            Toast.makeText(this, "请先添加并启用至少一张识别图片", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
    }
    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        status.text = "状态：已停止"
    }
    private fun renderRules() {
        list.removeAllViews()
        if (rules.isEmpty()) list.addView(TextView(this).apply { text = "尚未添加图片。建议从游戏截图中裁剪按钮，尽量只保留按钮本身。"; setPadding(0, 20, 0, 20) })
        rules.forEachIndexed { index, rule ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 14, 0, 14) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val check = CheckBox(this).apply {
                text = rule.name; isChecked = rule.enabled
                setOnCheckedChangeListener { _, checked ->
                    rules[index] = rules[index].copy(enabled = checked); RuleStore.save(this@MainActivity, rules)
                }
            }
            val delete = Button(this).apply {
                text = "删除"
                setOnClickListener {
                    File(rule.imagePath).delete()
                    rules.removeAt(index); RuleStore.save(this@MainActivity, rules); renderRules()
                }
            }
            top.addView(check, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(delete)
            val seek = SeekBar(this).apply {
                max = 40; progress = ((rule.threshold - .60) * 100).toInt().coerceIn(0, 40)
            }
            val label = TextView(this).apply { text = "相似度阈值：${"%.2f".format(rule.threshold)}" }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = .60 + p / 100.0
                    label.text = "相似度阈值：${"%.2f".format(v)}"
                    if (fromUser) { rules[index] = rules[index].copy(threshold = v); RuleStore.save(this@MainActivity, rules) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            row.addView(top); row.addView(label); row.addView(seek)
            list.addView(row)
            val line = android.view.View(this).apply { setBackgroundColor(0xffdddddd.toInt()) }
            list.addView(line, ViewGroup.LayoutParams(-1, 1))
        }
    }
}
