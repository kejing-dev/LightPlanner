package io.embodit.lightplanner.data

import android.content.Context
import io.embodit.lightplanner.domain.AnchorPose
import io.embodit.lightplanner.domain.LanguagePref
import io.embodit.lightplanner.domain.Plan
import io.embodit.lightplanner.domain.PlanCodec
import io.embodit.lightplanner.domain.Settings
import io.embodit.lightplanner.domain.Vec3
import java.io.File

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`; a fake in tests). */
interface AnchorGateway {
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}

/** The plan (files/plan.json, design `dataModel.storage`) and the settings on the device. */
interface PlanStore {
    fun loadPlan(): Plan?

    fun savePlan(plan: Plan?)

    fun loadSettings(): Settings

    fun saveSettings(settings: Settings)
}

class FilePlanStore(context: Context) : PlanStore {
    private val file = File(context.filesDir, "plan.json")
    private val prefs = context.getSharedPreferences("light_planner", Context.MODE_PRIVATE)

    override fun loadPlan(): Plan? = if (file.exists()) PlanCodec.decode(runCatching { file.readText() }.getOrNull()) else null

    override fun savePlan(plan: Plan?) {
        if (plan == null) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, "plan.json.tmp")
        tmp.writeText(PlanCodec.encode(plan))
        if (!tmp.renameTo(file)) {
            file.writeText(PlanCodec.encode(plan))
            tmp.delete()
        }
    }

    override fun loadSettings(): Settings =
        Settings(
            language = LanguagePref.entries.firstOrNull { it.tag == prefs.getString("lang", null) } ?: LanguagePref.SYSTEM,
            showSpacing = prefs.getBoolean("showSpacing", true),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            sound = prefs.getBoolean("sound", true),
        )

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("lang", settings.language.tag)
            .putBoolean("showSpacing", settings.showSpacing)
            .putBoolean("firstRunDone", settings.firstRunDone)
            .putBoolean("sound", settings.sound)
            .apply()
    }
}
