package com.example.pvzautoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TemplateRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val imagePath: String,
    val threshold: Double = 0.88,
    val enabled: Boolean = true,
    val cooldownMs: Long = 1500L
)

data class AutomationStep(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val templates: List<TemplateRule> = emptyList(),
    val waitAfterClickMs: Long = 1000L,
    val enabled: Boolean = true
)

data class AutomationProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<AutomationStep> = emptyList(),
    val loop: Boolean = false
)

object RuleStore {
    private const val PREF = "automation_projects"
    private const val KEY = "projects"
    private const val LEGACY_PREF = "rules"
    private const val LEGACY_KEY = "items"

    fun loadProjects(context: Context): MutableList<AutomationProject> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw != null) return runCatching { decodeProjects(JSONArray(raw)) }.getOrDefault(mutableListOf())

        // Migrate the previous flat image-rule list into one project and one step.
        val legacy = context.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE).getString(LEGACY_KEY, "[]") ?: "[]"
        val oldRules = runCatching {
            val arr = JSONArray(legacy)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TemplateRule(o.optLong("id").toString(), o.optString("name", "规则 ${i + 1}"), o.optString("path"),
                    o.optDouble("threshold", .88), o.optBoolean("enabled", true), o.optLong("cooldown", 1500L))
            }.filter { it.imagePath.isNotBlank() }
        }.getOrDefault(emptyList())
        val migrated = mutableListOf(AutomationProject(name = "默认项目", steps = listOf(AutomationStep(name = "步骤 1", templates = oldRules))))
        saveProjects(context, migrated)
        return migrated
    }

    fun saveProjects(context: Context, projects: List<AutomationProject>) {
        val arr = JSONArray()
        projects.forEach { p ->
            val steps = JSONArray()
            p.steps.forEach { s ->
                val templates = JSONArray()
                s.templates.forEach { t -> templates.put(JSONObject().put("id", t.id).put("name", t.name)
                    .put("path", t.imagePath).put("threshold", t.threshold).put("enabled", t.enabled).put("cooldown", t.cooldownMs)) }
                steps.put(JSONObject().put("id", s.id).put("name", s.name).put("templates", templates)
                    .put("wait", s.waitAfterClickMs).put("enabled", s.enabled))
            }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("steps", steps).put("loop", p.loop))
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun decodeProjects(arr: JSONArray): MutableList<AutomationProject> = MutableList(arr.length()) { i ->
        val p = arr.getJSONObject(i)
        val stepsJson = p.optJSONArray("steps") ?: JSONArray()
        val steps = List(stepsJson.length()) { j ->
            val s = stepsJson.getJSONObject(j)
            val templatesJson = s.optJSONArray("templates") ?: JSONArray()
            val templates = List(templatesJson.length()) { k ->
                val t = templatesJson.getJSONObject(k)
                TemplateRule(t.optString("id", UUID.randomUUID().toString()), t.optString("name", "图片 ${k + 1}"),
                    t.optString("path"), t.optDouble("threshold", .88), t.optBoolean("enabled", true), t.optLong("cooldown", 1500L))
            }
            AutomationStep(s.optString("id", UUID.randomUUID().toString()), s.optString("name", "步骤 ${j + 1}"),
                templates, s.optLong("wait", 1000L), s.optBoolean("enabled", true))
        }
        AutomationProject(p.optString("id", UUID.randomUUID().toString()), p.optString("name", "项目 ${i + 1}"),
            steps, p.optBoolean("loop", false))
    }

    fun templatesDir(context: Context): File = File(context.filesDir, "templates").apply { mkdirs() }
}
