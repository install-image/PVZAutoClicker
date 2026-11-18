package com.example.pvzautoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ClickRule(
    val id: Long,
    val name: String,
    val imagePath: String,
    val threshold: Double = 0.88,
    val enabled: Boolean = true,
    val cooldownMs: Long = 1500L
)

object RuleStore {
    private const val PREF = "rules"
    private const val KEY = "items"
    fun load(context: Context): MutableList<ClickRule> {
        val raw = context.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ClickRule(o.getLong("id"), o.getString("name"), o.getString("path"),
                o.optDouble("threshold", .88), o.optBoolean("enabled", true), o.optLong("cooldown", 1500))
        }
    }
    fun save(context: Context, rules: List<ClickRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("name", r.name).put("path", r.imagePath)
                .put("threshold", r.threshold).put("enabled", r.enabled).put("cooldown", r.cooldownMs))
        }
        context.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).apply()
    }
    fun templatesDir(context: Context): File = File(context.filesDir, "templates").apply { mkdirs() }
}
