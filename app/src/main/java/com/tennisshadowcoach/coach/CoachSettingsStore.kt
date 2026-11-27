package com.tennisshadowcoach.coach

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Local-only editable JSON, intentionally small until a real session history is needed. */
class CoachSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "coach_session_draft.json"))

    fun load(): CoachSessionDraft = runCatching {
        val root = JSONObject(file.baseFile.readText())
        CoachSessionDraft(
            dateIso = root.optString("date", LocalDate.now().toString()),
            coachNote = root.optString("coach_note"),
            focuses = root.optJSONArray("focuses").toFocuses()
        )
    }.getOrElse { CoachSessionDraft(LocalDate.now().toString()) }

    fun save(draft: CoachSessionDraft) {
        val contents = JSONObject().apply {
            put("date", draft.dateIso)
            put("coach_note", draft.coachNote)
            put("focuses", JSONArray().apply { draft.focuses.forEach { put(it.toJson()) } })
        }.toString(2)
        val stream = file.startWrite()
        try {
            stream.write(contents.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun JSONArray?.toFocuses(): List<WeeklyFocus> = buildList {
        if (this@toFocuses == null) return@buildList
        for (index in 0 until this@toFocuses.length()) {
            val item = this@toFocuses.optJSONObject(index) ?: continue
            val ruleObject = item.optJSONObject("rule")
            val rule = ruleObject?.let {
                FocusRule(
                    metric = runCatching { FocusMetric.valueOf(it.getString("metric")) }.getOrDefault(FocusMetric.LEADING_KNEE),
                    phase = it.optString("phase").let { name ->
                        if (name == "PEAK_SPEED") SwingPhase.IMPACT
                        else runCatching { SwingPhase.valueOf(name) }.getOrDefault(SwingPhase.IMPACT)
                    },
                    minimum = it.optDoubleOrNull("minimum"),
                    maximum = it.optDoubleOrNull("maximum"),
                    hint = it.optString("hint")
                )
            }
            add(WeeklyFocus(item.optString("id"), item.optString("title"), item.optBoolean("active"), rule))
        }
    }

    private fun WeeklyFocus.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("active", active)
        rule?.let { value -> put("rule", JSONObject().apply {
            put("metric", value.metric.name); put("phase", value.phase.name)
            value.minimum?.let { put("minimum", it) }; value.maximum?.let { put("maximum", it) }
            put("hint", value.hint)
        }) }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Float? =
        if (has(name) && !isNull(name)) optDouble(name).toFloat() else null
}
