package com.example.pvzautoclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var projectLabel: TextView
    private val projects = mutableListOf<AutomationProject>()
    private var currentProjectId: String = ""
    private var currentStepId: String? = null
    private val picker = 1001
    private val captureRequest = 1002
    private val projectionManager by lazy { getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(ClickAccessibilityService.EXTRA_MESSAGE) ?: return
            status.text = "状态：$message"
        }
    }

    private val currentProject: AutomationProject?
        get() = projects.firstOrNull { it.id == currentProjectId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projects.addAll(RuleStore.loadProjects(this))
        if (projects.isEmpty()) projects.add(AutomationProject(name = "默认项目"))
        currentProjectId = projects.first().id
        currentStepId = currentProject?.steps?.firstOrNull()?.id
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(ClickAccessibilityService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 12) }
        root.addView(TextView(this).apply { text = "PVZ 图片自动化任务"; textSize = 21f })
        projectLabel = TextView(this).apply { textSize = 16f; setPadding(0, 14, 0, 8) }
        root.addView(projectLabel)
        val projectButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        projectButtons.addView(Button(this).apply { text = "切换项目"; setOnClickListener { chooseProject() } }, LinearLayout.LayoutParams(0, -2, 1f))
        projectButtons.addView(Button(this).apply { text = "新建项目"; setOnClickListener { createProject() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(projectButtons)
        status = TextView(this).apply { text = "状态：待机；请先开启无障碍服务"; textSize = 13f; setPadding(0, 10, 0, 10) }
        root.addView(status)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply { text = "开启无障碍权限"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "添加步骤"; setOnClickListener { addStep() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "添加识别图片"; setOnClickListener { chooseImage() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "循环：切换"; setOnClickListener { toggleLoop() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        val run = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        run.addView(Button(this).apply { text = "开始项目"; setOnClickListener { startCapture() } }, LinearLayout.LayoutParams(0, -2, 1f))
        run.addView(Button(this).apply { text = "停止"; setOnClickListener { stopCapture() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(run)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun persist() = RuleStore.saveProjects(this, projects)

    private fun chooseProject() {
        if (projects.isEmpty()) return
        AlertDialog.Builder(this).setTitle("选择项目")
            .setItems(projects.map { it.name }.toTypedArray()) { _, which ->
                currentProjectId = projects[which].id
                currentStepId = currentProject?.steps?.firstOrNull()?.id
                render()
            }.show()
    }

    private fun createProject() {
        val input = EditText(this).apply { hint = "例如：每日自动闯关" }
        AlertDialog.Builder(this).setTitle("新建自动化项目").setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "项目 ${projects.size + 1}" }
                val project = AutomationProject(name = name)
                projects.add(project); currentProjectId = project.id; currentStepId = null
                persist(); render()
            }.setNegativeButton("取消", null).show()
    }

    private fun addStep() {
        val project = currentProject ?: return
        val input = EditText(this).apply { hint = "例如：点击开始按钮" }
        AlertDialog.Builder(this).setTitle("添加步骤").setView(input)
            .setPositiveButton("添加") { _, _ ->
                val step = AutomationStep(name = input.text.toString().trim().ifBlank { "步骤 ${project.steps.size + 1}" })
                updateProject(project.copy(steps = project.steps + step))
                currentStepId = step.id
                render()
            }.setNegativeButton("取消", null).show()
    }

    private fun toggleLoop() {
        val p = currentProject ?: return
        updateProject(p.copy(loop = !p.loop))
        Toast.makeText(this, if (currentProject?.loop == true) "项目循环已开启" else "项目循环已关闭", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun updateProject(updated: AutomationProject) {
        val index = projects.indexOfFirst { it.id == updated.id }
        if (index >= 0) projects[index] = updated
        persist()
    }

    private fun chooseImage() {
        val p = currentProject
        if (p == null || p.steps.isEmpty()) {
            Toast.makeText(this, "请先创建项目并添加步骤", Toast.LENGTH_LONG).show(); return
        }
        if (p.steps.none { it.id == currentStepId }) currentStepId = p.steps.last().id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivityForResult(intent, picker)
    }

    @Deprecated("Deprecated API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
                putExtra(CaptureService.EXTRA_PROJECT_ID, currentProjectId)
            }
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
                status.text = "状态：正在启动屏幕捕获…"
            } catch (e: Exception) { status.text = "状态：启动失败 ${e.message}" }
        }
        if (requestCode == picker && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val project = currentProject ?: return
            val stepIndex = project.steps.indexOfFirst { it.id == currentStepId }.takeIf { it >= 0 } ?: project.steps.lastIndex
            if (stepIndex < 0) return
            try {
                val name = "template_${System.currentTimeMillis()}.png"
                val dest = File(RuleStore.templatesDir(this), name)
                contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                    ?: throw IllegalStateException("无法读取所选图片")
                val oldStep = project.steps[stepIndex]
                val template = TemplateRule(name = "图片 ${oldStep.templates.size + 1}", imagePath = dest.absolutePath)
                val updatedStep = oldStep.copy(templates = oldStep.templates + template)
                updateProject(project.copy(steps = project.steps.toMutableList().also { it[stepIndex] = updatedStep }))
                currentStepId = updatedStep.id
                render()
                Toast.makeText(this, "图片已添加到${updatedStep.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this, "导入图片失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun startCapture() {
        val p = currentProject
        if (p == null || p.steps.none { it.enabled && it.templates.any { template -> template.enabled } }) {
            Toast.makeText(this, "项目至少需要一个启用的步骤和识别图片", Toast.LENGTH_LONG).show(); return
        }
        if (ClickAccessibilityService.instance == null) {
            Toast.makeText(this, "请先开启本应用的无障碍服务，再返回此页面", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        status.text = "状态：已发送停止指令"
    }

    private fun render() {
        content.removeAllViews()
        val project = currentProject
        projectLabel.text = "当前项目：${project?.name ?: "无"}    |    ${project?.steps?.size ?: 0} 个步骤    |    循环：${if (project?.loop == true) "开" else "关"}"
        if (project == null) return
        if (project.steps.isEmpty()) {
            content.addView(TextView(this).apply { text = "还没有步骤。点击“添加步骤”，然后为步骤添加识别图片。"; setPadding(0, 24, 0, 24) })
        }
        project.steps.forEachIndexed { stepIndex, step ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12)
                setBackgroundColor(if (step.id == currentStepId) 0xffe8f1ff.toInt() else 0xfff3f3f3.toInt())
            }
            val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            heading.addView(TextView(this).apply { text = "${stepIndex + 1}. ${step.name}"; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(Button(this).apply { text = "设为当前"; setOnClickListener { currentStepId = step.id; render() } })
            heading.addView(Button(this).apply { text = "等待时间"; setOnClickListener { editStepDelay(project, step) } })
            heading.addView(Button(this).apply { text = "删除步骤"; setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setMessage("删除步骤「${step.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        step.templates.forEach { File(it.imagePath).delete() }
                        val updated = project.copy(steps = project.steps.filterNot { it.id == step.id })
                        updateProject(updated); currentStepId = updated.steps.firstOrNull()?.id; render()
                    }.setNegativeButton("取消", null).show()
            } })
            card.addView(heading)
            card.addView(TextView(this).apply { text = "匹配策略：本步骤中任意一张启用图片匹配成功就点击；点击完成后进入下一步骤。等待 ${step.waitAfterClickMs} ms。"; textSize = 12f })
            step.templates.forEach { template ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val check = CheckBox(this).apply {
                    text = "${template.name}（阈值 ${"%.2f".format(template.threshold)}）"; isChecked = template.enabled
                    setOnCheckedChangeListener { _, checked -> updateTemplate(project, step, template.copy(enabled = checked)) }
                }
                line.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(Button(this).apply { text = "阈值"; setOnClickListener { editTemplateThreshold(project, step, template) } })
                line.addView(Button(this).apply { text = "删除"; setOnClickListener {
                    File(template.imagePath).delete()
                    updateStep(project, step.copy(templates = step.templates.filterNot { it.id == template.id })); render()
                } })
                card.addView(line)
            }
            if (step.templates.isEmpty()) card.addView(TextView(this).apply { text = "暂无图片。先点上方“添加识别图片”，并将此步骤设为当前步骤。"; textSize = 12f })
            content.addView(card)
            content.addView(android.view.View(this).apply { setBackgroundColor(0xffdddddd.toInt()) }, ViewGroup.LayoutParams(-1, 1))
        }
    }

    private fun editStepDelay(project: AutomationProject, step: AutomationStep) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(step.waitAfterClickMs.toString())
            hint = "点击完成后等待毫秒数（0-30000）"
        }
        AlertDialog.Builder(this).setTitle("${step.name}：下一步等待时间（毫秒）").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val delay = input.text.toString().toLongOrNull()?.coerceIn(0L, 30000L) ?: 1000L
                updateStep(project, step.copy(waitAfterClickMs = delay)); render()
            }.setNegativeButton("取消", null).show()
    }

    private fun editTemplateThreshold(project: AutomationProject, step: AutomationStep, template: TemplateRule) {
        val seek = SeekBar(this).apply { max = 39; progress = ((template.threshold - 0.60) * 100).toInt().coerceIn(0, 39) }
        val label = TextView(this).apply { text = "相似度阈值：${"%.2f".format(template.threshold)}"; setPadding(12, 8, 12, 8) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "相似度阈值：${"%.2f".format(0.60 + progress / 100.0)}"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8); addView(label); addView(seek) }
        AlertDialog.Builder(this).setTitle("${template.name} 匹配阈值").setView(box)
            .setPositiveButton("保存") { _, _ ->
                updateTemplate(project, step, template.copy(threshold = 0.60 + seek.progress / 100.0)); render()
            }.setNegativeButton("取消", null).show()
    }

    private fun updateTemplate(project: AutomationProject, step: AutomationStep, template: TemplateRule) {
        updateStep(project, step.copy(templates = step.templates.map { if (it.id == template.id) template else it }))
    }

    private fun updateStep(project: AutomationProject, step: AutomationStep) {
        updateProject(project.copy(steps = project.steps.map { if (it.id == step.id) step else it }))
    }
}
