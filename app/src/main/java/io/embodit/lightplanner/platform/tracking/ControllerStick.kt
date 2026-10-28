package io.embodit.lightplanner.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.lightplanner.domain.Quat4
import io.embodit.lightplanner.domain.Vec3
import io.embodit.lightplanner.platform.LpLog
import kotlin.math.abs

/**
 * The controllers: the pointing ray (0.1.1, taste profile D1: the placing preview follows the controller ray, not
 * the head) and the thumbstick X axis (v0.1.1, device feedback: the stick turns the selected piece),
 * from `ControllerTrackingProvider`'s action listener (SDK doc "Obtain controller input actions"; Full
 * Space only). The listener runs on the data source thread at the device rate: it only stores the
 * value; the ECS system reads it once per frame. Hand gestures are untouched (another input path).
 */
class ControllerStick {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    /** The controller whose trigger was touched last aims (the right one at first, Spatial Ruler `PointerSource`). */
    @Volatile private var useRight = true

    /** Debug: a ray that wins over the controllers until [debugRayUntilMs] (emulator QA has no controller pose). */
    @Volatile private var debugRay: Pair<Vec3, Vec3>? = null
    @Volatile private var debugRayUntilMs = 0L

    // the stick pushed further of the two controllers; the listener only stores values (data source thread)
    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            x = if (abs(r) >= abs(l)) r else l
            val lt = a.left.triggerValue
            val rt = a.right.triggerValue
            if (useRight && lt > SWITCH_AT && rt < REST_BELOW) useRight = false
            if (!useRight && rt > SWITCH_AT && lt < REST_BELOW) useRight = true
            atMs = SystemClock.elapsedRealtime()
            actions++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { LpLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        LpLog.i("STICK start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        x = 0f
        actions = 0L
    }

    /** Thumbstick X in [-1, 1] (right +); 0 without a controller or when the last report is stale. */
    fun x(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        val p = provider ?: return 0f
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return 0f
        if (nowMs - atMs > STALE_MS) return 0f
        return x.coerceIn(-1f, 1f)
    }

    /**
     * The aiming controller's ray in Stage metres (origin, unit direction along the pose's -Z), read on the main
     * thread once per frame; null without a tracked controller. Same tracking space as the HMD pose the scene uses.
     */
    fun ray(nowMs: Long): Pair<Vec3, Vec3>? {
        if (nowMs < debugRayUntilMs) return debugRay
        val p = provider ?: return null
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = (if (useRight) data.right ?: data.left else data.left ?: data.right) ?: return null
        val origin = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val dir = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w).rotate(Vec3.AHEAD)
        if (!origin.isFinite() || !dir.isFinite() || dir.length < 0.5f || origin.length < 1e-3f) return null
        return origin to dir.normalized()
    }

    /** Debug `ray`: aims from [origin] along [dir] for [ms]. */
    fun debugAim(origin: Vec3, dir: Vec3, ms: Long) {
        debugRay = origin to dir.normalized()
        debugRayUntilMs = SystemClock.elapsedRealtime() + ms
    }

    /** Debug `stick`: holds [value] for [ms]. */
    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)}"

    private companion object {
        const val STALE_MS = 500L
        const val SWITCH_AT = 0.15f
        const val REST_BELOW = 0.05f
    }
}
