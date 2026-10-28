package io.embodit.lightplanner.domain

import org.json.JSONArray
import org.json.JSONObject

/** Pose of a world anchor in Stage space. */
data class AnchorPose(val position: Vec3, val rotation: Quat4) {
    fun toLocal(world: Vec3): Vec3 = rotation.conjugate().rotate(world - position)

    fun toWorld(local: Vec3): Vec3 = rotation.rotate(local) + position

    fun toLocalDir(world: Vec3): Vec3 = rotation.conjugate().rotate(world)

    fun toWorldDir(local: Vec3): Vec3 = rotation.rotate(local)
}

enum class LanguagePref(val tag: String) { SYSTEM("system"), ZH("zh"), EN("en") }

/** Settings (design `dataModel.settings`). */
data class Settings(val language: LanguagePref = LanguagePref.SYSTEM, val showSpacing: Boolean = true, val firstRunDone: Boolean = false, val sound: Boolean = true)

/**
 * Feedback sounds stay quiet when pinching fast (taste profile D15, 0.1.1): each kind plays at most once per its
 * gap; a refused pinch (reject) has the longest one, since repeated misses were the noisy case.
 */
class SoundGate(private val gapsMs: Map<String, Long> = mapOf("reject" to 800L, "select" to 300L, "place" to 150L, "snap" to 400L)) {
    private val last = HashMap<String, Long>()

    fun allow(kind: String, nowMs: Long): Boolean {
        val gap = gapsMs[kind] ?: 200L
        val prev = last[kind]
        if (prev != null && nowMs - prev < gap) return false
        last[kind] = nowMs
        return true
    }
}

/**
 * One saved lamp: its Stage position at save time, its anchor and where that anchor was then. On
 * restore the anchor's new pose moves the lamp with it; a lamp whose anchor is gone follows the shift of
 * a lamp whose anchor was found (design `dataModel.restore`, the "backup offset").
 */
data class SavedLamp(val id: String, val no: Int, val pos: Vec3, val beam: Beam, val output: Output, val cct: Cct, val anchorId: String?, val anchorPos: Vec3?)

/** The one plan on the device (design `dataModel.plan`). */
data class Plan(val lamps: List<SavedLamp>, val manualCeiling: Float?, val nextNo: Int, val updatedAt: Long)

object PlanCodec {
    fun encode(p: Plan): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("workPlaneHeight", LightMath.WORK_PLANE_M.toDouble())
        if (p.manualCeiling != null) o.put("manualCeiling", p.manualCeiling.toDouble())
        o.put("nextNo", p.nextNo)
        o.put("updatedAt", p.updatedAt)
        val arr = JSONArray()
        for (l in p.lamps) {
            val j = JSONObject()
            j.put("id", l.id)
            j.put("no", l.no)
            j.put("pos", vec(l.pos))
            j.put("beam", l.beam.tag)
            j.put("output", l.output.tag)
            j.put("cct", l.cct.tag)
            if (l.anchorId != null) j.put("anchorId", l.anchorId)
            if (l.anchorPos != null) j.put("anchorPos", vec(l.anchorPos))
            arr.put(j)
        }
        o.put("lamps", arr)
        return o.toString()
    }

    fun decode(s: String?): Plan? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            val arr = o.optJSONArray("lamps") ?: JSONArray()
            val lamps = ArrayList<SavedLamp>()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                lamps +=
                    SavedLamp(
                        id = j.getString("id"),
                        no = j.getInt("no"),
                        pos = vec(j.getJSONArray("pos")),
                        beam = Beam.byTag(j.optString("beam")) ?: Beam.DEFAULT,
                        output = Output.byTag(j.optString("output")) ?: Output.DEFAULT,
                        cct = Cct.byTag(j.optString("cct")) ?: Cct.DEFAULT,
                        anchorId = j.optString("anchorId").takeIf { it.isNotBlank() },
                        anchorPos = j.optJSONArray("anchorPos")?.let { vec(it) },
                    )
            }
            Plan(lamps, if (o.has("manualCeiling")) o.getDouble("manualCeiling").toFloat() else null, o.optInt("nextNo", lamps.size + 1), o.optLong("updatedAt", 0L))
        } catch (t: Throwable) {
            null
        }
    }

    private fun vec(v: Vec3): JSONArray = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

    private fun vec(a: JSONArray): Vec3 = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
}

/** Number formatting shared by panels and logs: metres with two decimals, centimetres whole. */
object Units {
    fun m(v: Float): String = String.format(java.util.Locale.US, "%.2f m", v)

    fun cm(v: Float): String = "${Math.round(v * 100f)} cm"
}
